package com.sdp.trading;

import com.sdp.audit.AuditService;
import com.sdp.contracts.PendingTrade;
import com.sdp.contracts.PendingTradeId;
import com.sdp.contracts.Side;
import com.sdp.contracts.TradeCommand;
import com.sdp.contracts.TradeCommandResult;
import com.sdp.contracts.TradeRejected;
import com.sdp.contracts.TradeRequest;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.stream.function.StreamBridge;

import reactor.core.publisher.Mono;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TradeServiceTest {

    private final TradeRepository tradeRepository = mock(TradeRepository.class);
    private final AuditService auditService = mock(AuditService.class);
    private final StreamBridge streamBridge = mock(StreamBridge.class);
    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private final TradeService service = new TradeService(tradeRepository, auditService, streamBridge, objectMapper);

    @BeforeEach
    void echoBackWhateverIsSaved() {
        when(tradeRepository.save(any())).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(auditService.record(any(), any(), any(), any())).thenReturn(Mono.empty());
    }

    private TradeCommand command(String type, Object payload) {
        return new TradeCommand(UUID.randomUUID().toString(), "trader1", type, payload);
    }

    private TradeCommandResult captureReply() {
        var captor = org.mockito.ArgumentCaptor.forClass(TradeCommandResult.class);
        verify(streamBridge).send(eq("tradeResponses-out-0"), captor.capture());
        return captor.getValue();
    }

    @Test
    void createTradeHoldsAPendingTradeAndRepliesWithItWithoutPersisting() {
        TradeCommand command = command("CREATE_TRADE", new TradeRequest("EUR/USD", Side.BUY, new BigDecimal("1.0850"), new BigDecimal("1000000")));

        service.handle(command).block();

        TradeCommandResult reply = captureReply();
        assertThat(reply.correlationId()).isEqualTo(command.correlationId());
        assertThat(reply.type()).isEqualTo("TRADE_PENDING");
        PendingTrade pending = objectMapper.convertValue(reply.payload(), PendingTrade.class);
        assertThat(pending.id()).isNotBlank();
        assertThat(pending.symbol()).isEqualTo("EUR/USD");
        verify(tradeRepository, never()).save(any());
    }

    @Test
    void createTradeWithNonPositiveQuantityRejectsAndBroadcastsWithoutHoldingAPending() {
        TradeCommand command = command("CREATE_TRADE", new TradeRequest("EUR/USD", Side.BUY, new BigDecimal("1.0850"), new BigDecimal("0")));

        service.handle(command).block();

        TradeCommandResult reply = captureReply();
        assertThat(reply.type()).isEqualTo("TRADE_REJECTED");
        verify(streamBridge).send(eq("tradeRejected-out-0"), any(TradeRejected.class));
        verify(auditService).record(eq(null), eq("trader1"), eq("TRADE_REJECTED"), any());
    }

    @Test
    void createTradeForAnUnknownSymbolRejects() {
        TradeCommand command = command("CREATE_TRADE", new TradeRequest("XAU/USD", Side.BUY, new BigDecimal("2000"), new BigDecimal("100")));

        service.handle(command).block();

        assertThat(captureReply().type()).isEqualTo("TRADE_REJECTED");
    }

    @Test
    void confirmTradePersistsAndBroadcastsTradeCreatedWithNoReply() {
        TradeCommand created = command("CREATE_TRADE", new TradeRequest("GBP/USD", Side.SELL, new BigDecimal("1.2650"), new BigDecimal("500000")));
        service.handle(created).block();
        PendingTrade pending = objectMapper.convertValue(captureReply().payload(), PendingTrade.class);

        TradeCommand confirm = command("CONFIRM_TRADE", new PendingTradeId(pending.id()));
        service.handle(confirm).block();

        verify(tradeRepository).save(any());
        verify(streamBridge).send(eq("tradeCreated-out-0"), any(com.sdp.contracts.Trade.class));
        verify(auditService).record(eq(null), eq("trader1"), eq("TRADE_EXECUTED"), any());
        // No reply for CONFIRM_TRADE: only the two calls above should exist on tradeResponses-out-0.
        verify(streamBridge, org.mockito.Mockito.times(1)).send(eq("tradeResponses-out-0"), any());
    }

    @Test
    void confirmTradeWithAnUnknownIdDoesNothing() {
        service.handle(command("CONFIRM_TRADE", new PendingTradeId("unknown"))).block();

        verify(tradeRepository, never()).save(any());
        verify(streamBridge, never()).send(eq("tradeResponses-out-0"), any());
    }

    @Test
    void cancelTradeRepliesWithTheCancelledPendingTrade() {
        TradeCommand created = command("CREATE_TRADE", new TradeRequest("USD/JPY", Side.BUY, new BigDecimal("149.50"), new BigDecimal("250000")));
        service.handle(created).block();
        PendingTrade pending = objectMapper.convertValue(captureReply().payload(), PendingTrade.class);

        TradeCommand cancel = command("CANCEL_TRADE", new PendingTradeId(pending.id()));
        service.handle(cancel).block();

        var captor = org.mockito.ArgumentCaptor.forClass(TradeCommandResult.class);
        verify(streamBridge, org.mockito.Mockito.times(2)).send(eq("tradeResponses-out-0"), captor.capture());
        TradeCommandResult cancelReply = captor.getAllValues().get(1);
        assertThat(cancelReply.correlationId()).isEqualTo(cancel.correlationId());
        assertThat(cancelReply.type()).isEqualTo("TRADE_CANCELLED");
    }

    @Test
    void cancelTradeWithAnUnknownIdRepliesNoop() {
        TradeCommand cancel = command("CANCEL_TRADE", new PendingTradeId("unknown"));
        service.handle(cancel).block();

        TradeCommandResult reply = captureReply();
        assertThat(reply.correlationId()).isEqualTo(cancel.correlationId());
        assertThat(reply.type()).isEqualTo("NOOP");
    }

    @Test
    void getTradeHistoryRepliesWithThePersistedHistory() {
        Trade trade = new Trade("t1", "EUR/USD", Side.BUY, new BigDecimal("1.0850"), new BigDecimal("100000"), java.time.Instant.now());
        when(tradeRepository.findAllByOrderByTimestampAsc()).thenReturn(reactor.core.publisher.Flux.just(trade));

        TradeCommand command = command("GET_TRADE_HISTORY", null);
        service.handle(command).block();

        TradeCommandResult reply = captureReply();
        assertThat(reply.type()).isEqualTo("TRADE_HISTORY");
        assertThat(reply.payload()).asList().hasSize(1);
    }
}

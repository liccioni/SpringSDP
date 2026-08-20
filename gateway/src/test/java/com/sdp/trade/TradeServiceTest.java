package com.sdp.trade;

import com.sdp.common.Side;
import com.sdp.common.Trade;
import com.sdp.contracts.PendingTradeId;
import com.sdp.contracts.TradeCommand;
import com.sdp.contracts.TradeCommandResult;
import com.sdp.eventbus.EventBus;
import com.sdp.session.Session;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.cloud.stream.function.StreamBridge;

import reactor.test.StepVerifier;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * TradeService is now a pure forwarding adapter (see ADR 0022's update,
 * issue #92) - these tests exercise the request/reply plumbing itself
 * (correlationId round-trip via the tradeResponseConsumer @Bean, simulating
 * the Backend/Trading Service's reply) rather than trading-domain logic
 * (validation, persistence - that moved to trading-service's own
 * TradeServiceTest).
 */
class TradeServiceTest {

    private final EventBus eventBus = new EventBus();
    private final StreamBridge streamBridge = mock(StreamBridge.class);
    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private final TradeService service = new TradeService(eventBus, streamBridge, objectMapper);
    private final Session session = new Session("connection-1", "trader1", Set.of("trader"));

    private TradeCommand captureSentCommand() {
        ArgumentCaptor<TradeCommand> captor = ArgumentCaptor.forClass(TradeCommand.class);
        verify(streamBridge).send(eq("tradeRequests-out-0"), captor.capture());
        return captor.getValue();
    }

    @Test
    void requestTradeSendsACreateTradeCommandAndResolvesFromThePendingReply() {
        TradeRequest request = new TradeRequest("EUR/USD", Side.BUY, new BigDecimal("1.0851"), new BigDecimal("1000000"));

        var resultMono = service.requestTrade(request, session);
        TradeCommand sent = captureSentCommand();
        assertThat(sent.type()).isEqualTo("CREATE_TRADE");
        assertThat(sent.submittedBy()).isEqualTo("trader1");
        assertThat(sent.roles()).containsExactly("trader");

        com.sdp.contracts.PendingTrade pending = new com.sdp.contracts.PendingTrade(
                UUID.randomUUID().toString(), "EUR/USD", com.sdp.contracts.Side.BUY, request.price(), request.quantity(), Instant.now());
        service.tradeResponseConsumer().accept(new TradeCommandResult(sent.correlationId(), "TRADE_PENDING", pending));

        PendingTrade result = resultMono.block(Duration.ofSeconds(2));
        assertThat(result.id()).isEqualTo(pending.id());
        assertThat(result.symbol()).isEqualTo("EUR/USD");
        assertThat(result.side()).isEqualTo(Side.BUY);
    }

    @Test
    void requestTradeResolvesEmptyWhenTheReplyIsARejection() {
        TradeRequest request = new TradeRequest("EUR/USD", Side.BUY, new BigDecimal("1.0851"), new BigDecimal("0"));

        var resultMono = service.requestTrade(request, session);
        TradeCommand sent = captureSentCommand();

        service.tradeResponseConsumer().accept(new TradeCommandResult(sent.correlationId(), "TRADE_REJECTED", null));

        assertThat(resultMono.block(Duration.ofSeconds(2))).isNull();
    }

    @Test
    void confirmTradeSendsAConfirmTradeCommandAndResolvesImmediatelyWithoutAReply() {
        Trade trade = service.confirmTrade("pending-1", session).block(Duration.ofSeconds(2));

        assertThat(trade).isNull();
        ArgumentCaptor<TradeCommand> captor = ArgumentCaptor.forClass(TradeCommand.class);
        verify(streamBridge).send(eq("tradeRequests-out-0"), captor.capture());
        TradeCommand sent = captor.getValue();
        assertThat(sent.type()).isEqualTo("CONFIRM_TRADE");
        assertThat(objectMapper.convertValue(sent.payload(), PendingTradeId.class).id()).isEqualTo("pending-1");
    }

    @Test
    void cancelTradeResolvesFromTheCancelledReply() {
        var resultMono = service.cancelTrade("pending-1", session);
        TradeCommand sent = captureSentCommand();
        assertThat(sent.type()).isEqualTo("CANCEL_TRADE");

        com.sdp.contracts.PendingTrade pending = new com.sdp.contracts.PendingTrade(
                "pending-1", "GBP/USD", com.sdp.contracts.Side.SELL, new BigDecimal("1.2650"), new BigDecimal("500000"), Instant.now());
        service.tradeResponseConsumer().accept(new TradeCommandResult(sent.correlationId(), "TRADE_CANCELLED", pending));

        PendingTrade result = resultMono.block(Duration.ofSeconds(2));
        assertThat(result.id()).isEqualTo("pending-1");
    }

    @Test
    void cancelTradeResolvesEmptyOnANoopReply() {
        var resultMono = service.cancelTrade("unknown", session);
        TradeCommand sent = captureSentCommand();

        service.tradeResponseConsumer().accept(new TradeCommandResult(sent.correlationId(), "NOOP", null));

        assertThat(resultMono.block(Duration.ofSeconds(2))).isNull();
    }

    @Test
    void historyResolvesFromTheHistoryReply() {
        com.sdp.contracts.TradeHistoryQuery query = new com.sdp.contracts.TradeHistoryQuery(50, null, null, null);

        var pageMono = service.history(query, "client-correlation-1");
        TradeCommand sent = captureSentCommand();
        assertThat(sent.type()).isEqualTo("GET_TRADE_HISTORY");
        assertThat(sent.correlationId()).isEqualTo("client-correlation-1");
        assertThat(sent.payload()).isEqualTo(query);

        com.sdp.contracts.Trade older = new com.sdp.contracts.Trade(
                "1", "EUR/USD", com.sdp.contracts.Side.BUY, new BigDecimal("1.08"), new BigDecimal("100"), Instant.parse("2026-01-01T00:00:00Z"));
        com.sdp.contracts.TradeHistoryPage page = new com.sdp.contracts.TradeHistoryPage(List.of(older), null, false);
        service.tradeResponseConsumer().accept(new TradeCommandResult(sent.correlationId(), "TRADE_HISTORY", page));

        com.sdp.contracts.TradeHistoryPage result = pageMono.block(Duration.ofSeconds(2));
        assertThat(result.rows()).hasSize(1);
        assertThat(result.rows().get(0).id()).isEqualTo("1");
        assertThat(result.rows().get(0).symbol()).isEqualTo("EUR/USD");
        assertThat(result.hasMore()).isFalse();
    }

    @Test
    void historyFallsBackToAGeneratedCorrelationIdWhenTheClientSuppliedNone() {
        com.sdp.contracts.TradeHistoryQuery query = new com.sdp.contracts.TradeHistoryQuery(50, null, null, null);

        var pageMono = service.history(query, null);
        TradeCommand sent = captureSentCommand();
        assertThat(sent.correlationId()).isNotBlank();

        com.sdp.contracts.TradeHistoryPage page = new com.sdp.contracts.TradeHistoryPage(List.of(), null, false);
        service.tradeResponseConsumer().accept(new TradeCommandResult(sent.correlationId(), "TRADE_HISTORY", page));

        assertThat(pageMono.block(Duration.ofSeconds(2)).rows()).isEmpty();
    }

    @Test
    void tradeCreatedConsumerRelaysOntoTheEventBus() {
        com.sdp.contracts.Trade trade = new com.sdp.contracts.Trade(
                "1", "EUR/USD", com.sdp.contracts.Side.BUY, new BigDecimal("1.0850"), new BigDecimal("1000000"), Instant.now());

        StepVerifier.create(eventBus.events())
                .then(() -> service.tradeCreatedConsumer().accept(trade))
                .assertNext(event -> {
                    assertThat(event).isInstanceOf(Trade.class);
                    assertThat(((Trade) event).symbol()).isEqualTo("EUR/USD");
                })
                .thenCancel()
                .verify();
    }

    @Test
    void tradeRejectedConsumerRelaysOntoTheEventBus() {
        com.sdp.contracts.TradeRejected rejected = new com.sdp.contracts.TradeRejected(
                "EUR/USD", com.sdp.contracts.Side.SELL, new BigDecimal("1.0850"), new BigDecimal("0"), "quantity must be greater than zero");

        StepVerifier.create(eventBus.events())
                .then(() -> service.tradeRejectedConsumer().accept(rejected))
                .assertNext(event -> {
                    assertThat(event).isInstanceOf(TradeRejected.class);
                    assertThat(((TradeRejected) event).reason()).isEqualTo("quantity must be greater than zero");
                })
                .thenCancel()
                .verify();
    }

    @Test
    void aReplyForAnUnknownCorrelationIdIsIgnored() {
        service.tradeResponseConsumer().accept(new TradeCommandResult("unknown-correlation-id", "TRADE_PENDING", null));
        // No exception, nothing to assert beyond "didn't blow up" - there's
        // no pending sink for this id (already timed out, or never existed).
    }
}

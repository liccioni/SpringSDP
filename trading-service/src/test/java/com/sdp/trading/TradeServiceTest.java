package com.sdp.trading;

import com.sdp.audit.AuditService;
import com.sdp.contracts.Side;
import com.sdp.contracts.TradeRejected;
import com.sdp.contracts.TradeRequest;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.stream.function.StreamBridge;

import reactor.core.publisher.Mono;

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
    private final TradeService service = new TradeService(tradeRepository, auditService, streamBridge);

    @BeforeEach
    void echoBackWhateverIsSaved() {
        when(tradeRepository.save(any())).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(auditService.record(any(), any(), any(), any())).thenReturn(Mono.empty());
    }

    @Test
    void executePersistsTheTradeAndBroadcastsTradeCreated() {
        TradeRequest request = new TradeRequest("EUR/USD", Side.BUY, new BigDecimal("1.0850"), new BigDecimal("1000000"));

        Trade trade = service.execute(request, "trader1").block();

        assertThat(trade.id()).isNotBlank();
        assertThat(trade.symbol()).isEqualTo("EUR/USD");
        assertThat(trade.side()).isEqualTo(Side.BUY);
        assertThat(trade.price()).isEqualByComparingTo("1.0850");
        assertThat(trade.quantity()).isEqualByComparingTo("1000000");

        verify(streamBridge).send(eq("tradeCreated-out-0"), any(com.sdp.contracts.Trade.class));
        verify(auditService).record(eq(null), eq("trader1"), eq("TRADE_EXECUTED"), any());
    }

    @Test
    void rejectRecordsAnAuditEventAndBroadcastsTradeRejectedWithoutPersisting() {
        TradeRequest request = new TradeRequest("EUR/USD", Side.BUY, new BigDecimal("1.0850"), new BigDecimal("0"));

        service.reject(request, "trader1", "quantity must be greater than zero").block();

        verify(tradeRepository, never()).save(any());
        verify(streamBridge).send(eq("tradeRejected-out-0"), any(TradeRejected.class));
        verify(auditService).record(eq(null), eq("trader1"), eq("TRADE_REJECTED"), any());
    }
}

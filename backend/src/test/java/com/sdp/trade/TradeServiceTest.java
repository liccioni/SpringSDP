package com.sdp.trade;

import com.sdp.common.Side;
import com.sdp.common.Trade;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

class TradeServiceTest {

    private final TradeService service = new TradeService();

    @Test
    void createTradeStoresAndReturnsATrade() {
        TradeRequest request = new TradeRequest("EUR/USD", Side.BUY, new BigDecimal("1.0851"), new BigDecimal("1000000"));

        Trade trade = service.createTrade(request);

        assertThat(trade.id()).isNotBlank();
        assertThat(trade.symbol()).isEqualTo("EUR/USD");
        assertThat(trade.side()).isEqualTo(Side.BUY);
        assertThat(trade.price()).isEqualTo(request.price());
        assertThat(trade.quantity()).isEqualTo(request.quantity());
        assertThat(trade.timestamp()).isNotNull();
        assertThat(service.blotter()).containsExactly(trade);
    }

    @Test
    void createTradeEmitsOnTradeCreatedStream() {
        TradeRequest request = new TradeRequest("GBP/USD", Side.SELL, new BigDecimal("1.2650"), new BigDecimal("500000"));

        StepVerifier.create(service.tradeCreated())
                .then(() -> service.createTrade(request))
                .assertNext(trade -> assertThat(trade.symbol()).isEqualTo("GBP/USD"))
                .thenCancel()
                .verify();
    }

    @Test
    void eachTradeGetsAUniqueId() {
        TradeRequest request = new TradeRequest("USD/JPY", Side.BUY, new BigDecimal("149.50"), new BigDecimal("100000"));

        Trade first = service.createTrade(request);
        Trade second = service.createTrade(request);

        assertThat(first.id()).isNotEqualTo(second.id());
    }
}

package com.sdp.common;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class DomainModelTest {

    @Test
    void priceTickExposesFields() {
        Instant now = Instant.parse("2026-08-05T12:00:00Z");
        BigDecimal bid = new BigDecimal("1.0850");
        BigDecimal ask = new BigDecimal("1.0852");
        PriceTick tick = new PriceTick("EUR/USD", bid, ask, now);

        assertThat(tick.symbol()).isEqualTo("EUR/USD");
        assertThat(tick.bid()).isEqualTo(bid);
        assertThat(tick.ask()).isEqualTo(ask);
        assertThat(tick.timestamp()).isEqualTo(now);
    }

    @Test
    void tradeExposesFields() {
        Instant now = Instant.parse("2026-08-05T12:00:00Z");
        BigDecimal price = new BigDecimal("1.0851");
        BigDecimal quantity = new BigDecimal("1000000");
        Trade trade = new Trade("trade-1", "EUR/USD", Side.BUY, price, quantity, now);

        assertThat(trade.id()).isEqualTo("trade-1");
        assertThat(trade.symbol()).isEqualTo("EUR/USD");
        assertThat(trade.side()).isEqualTo(Side.BUY);
        assertThat(trade.price()).isEqualTo(price);
        assertThat(trade.quantity()).isEqualTo(quantity);
        assertThat(trade.timestamp()).isEqualTo(now);
    }
}

package com.sdp.common;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class DomainModelTest {

    @Test
    void priceTickExposesFields() {
        Instant now = Instant.parse("2026-08-05T12:00:00Z");
        PriceTick tick = new PriceTick("EUR/USD", 1.0850, 1.0852, now);

        assertThat(tick.symbol()).isEqualTo("EUR/USD");
        assertThat(tick.bid()).isEqualTo(1.0850);
        assertThat(tick.ask()).isEqualTo(1.0852);
        assertThat(tick.timestamp()).isEqualTo(now);
    }

    @Test
    void tradeExposesFields() {
        Instant now = Instant.parse("2026-08-05T12:00:00Z");
        Trade trade = new Trade("trade-1", "EUR/USD", Side.BUY, 1.0851, 1_000_000, now);

        assertThat(trade.id()).isEqualTo("trade-1");
        assertThat(trade.symbol()).isEqualTo("EUR/USD");
        assertThat(trade.side()).isEqualTo(Side.BUY);
        assertThat(trade.price()).isEqualTo(1.0851);
        assertThat(trade.quantity()).isEqualTo(1_000_000);
        assertThat(trade.timestamp()).isEqualTo(now);
    }
}

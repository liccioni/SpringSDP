package com.sdp.market;

import com.sdp.common.PriceTick;
import com.sdp.common.Side;
import com.sdp.trade.TradeRejected;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SymbolSubscriptionTest {

    private final SymbolSubscription subscriptions = new SymbolSubscription();

    @Test
    void hidesPriceTicksForSymbolsNotSubscribedTo() {
        assertThat(subscriptions.isVisible(priceTick("EUR/USD"))).isFalse();
    }

    @Test
    void showsPriceTicksForSubscribedSymbols() {
        subscriptions.subscribe("EUR/USD");

        assertThat(subscriptions.isVisible(priceTick("EUR/USD"))).isTrue();
    }

    @Test
    void hidesPriceTicksAfterUnsubscribing() {
        subscriptions.subscribe("EUR/USD");
        subscriptions.unsubscribe("EUR/USD");

        assertThat(subscriptions.isVisible(priceTick("EUR/USD"))).isFalse();
    }

    @Test
    void alwaysShowsNonPriceTickEvents() {
        TradeRejected rejection = new TradeRejected("EUR/USD", Side.BUY, BigDecimal.ONE, BigDecimal.ZERO, "quantity must be greater than zero");

        assertThat(subscriptions.isVisible(rejection)).isTrue();
    }

    private PriceTick priceTick(String symbol) {
        return new PriceTick(symbol, new BigDecimal("1.0850"), new BigDecimal("1.0852"), Instant.now());
    }
}

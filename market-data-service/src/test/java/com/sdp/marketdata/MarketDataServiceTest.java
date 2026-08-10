package com.sdp.marketdata;

import com.sdp.contracts.PriceTick;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.stream.function.StreamBridge;

import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class MarketDataServiceTest {

    private static final Set<String> KNOWN_SYMBOLS = Set.of("EUR/USD", "GBP/USD", "USD/JPY");

    private final MarketDataService service = new MarketDataService(mock(StreamBridge.class));

    @Test
    void streamsOneTickPerSymbolOnEachInterval() {
        StepVerifier.withVirtualTime(() -> service.priceTicks().take(6))
                .thenAwait(Duration.ofSeconds(2))
                .expectNextCount(6)
                .verifyComplete();
    }

    @Test
    void ticksAreForKnownSymbolsWithBidBelowAsk() {
        List<PriceTick> ticks = new ArrayList<>();

        StepVerifier.withVirtualTime(() -> service.priceTicks().take(3))
                .thenAwait(Duration.ofSeconds(1))
                .recordWith(() -> ticks)
                .expectNextCount(3)
                .verifyComplete();

        assertThat(ticks).extracting(PriceTick::symbol).allMatch(KNOWN_SYMBOLS::contains);
        assertThat(ticks).allSatisfy(tick -> assertThat(tick.bid()).isLessThan(tick.ask()));
    }
}

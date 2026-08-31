package com.sdp.marketdata;

import com.sdp.contracts.PriceTick;
import com.sdp.contracts.SymbolCatalog;

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

    private static final Set<String> KNOWN_SYMBOLS = Set.copyOf(SymbolCatalog.ALL_SYMBOLS);

    private final MarketDataService service = new MarketDataService(mock(StreamBridge.class));

    @Test
    void streamsOneTickPerSymbolOnEachInterval() {
        int symbolCount = SymbolCatalog.ALL_SYMBOLS.size();
        StepVerifier.withVirtualTime(() -> service.priceTicks().take(symbolCount))
                .thenAwait(Duration.ofSeconds(2))
                .expectNextCount(symbolCount)
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

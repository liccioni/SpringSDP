package com.sdp.market;

import com.sdp.common.PriceTick;
import com.sdp.eventbus.DomainEvent;
import com.sdp.eventbus.EventBus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MarketDataServiceTest {

    private static final Set<String> KNOWN_SYMBOLS = Set.of("EUR/USD", "GBP/USD", "USD/JPY");

    private final EventBus eventBus = new EventBus();
    private final MarketDataService service = new MarketDataService(eventBus);

    @Test
    void knowsTheTradeableSymbols() {
        assertThat(service.symbols()).isEqualTo(KNOWN_SYMBOLS);
    }

    @Test
    void priceTickConsumerRelaysOntoTheEventBus() {
        List<DomainEvent> received = new ArrayList<>();
        eventBus.events().subscribe(received::add);

        com.sdp.contracts.PriceTick tick = new com.sdp.contracts.PriceTick(
                "EUR/USD", new BigDecimal("1.0849"), new BigDecimal("1.0851"), Instant.now());

        service.priceTickConsumer().accept(tick);

        assertThat(received).hasSize(1);
        PriceTick relayed = (PriceTick) received.get(0);
        assertThat(relayed.symbol()).isEqualTo("EUR/USD");
        assertThat(relayed.bid()).isEqualByComparingTo("1.0849");
        assertThat(relayed.ask()).isEqualByComparingTo("1.0851");
        assertThat(relayed.eventType()).isEqualTo("PRICE_TICK");
    }
}

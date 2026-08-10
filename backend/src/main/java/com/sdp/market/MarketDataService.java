package com.sdp.market;

import com.sdp.common.PriceTick;
import com.sdp.eventbus.EventBus;

import java.util.Set;
import java.util.function.Consumer;

import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Service;

/**
 * Known tradeable symbols (used by TradeService's validation) and the
 * monolith's temporary consumer of PRICE_TICK messages from the Market Data
 * Service's RabbitMQ fanout exchange (see ADR 0022) - relays each one onto
 * the in-process EventBus so SdpWebSocketHandler's existing
 * subscription-filtered delivery to browsers is unchanged.
 *
 * Price generation itself moved to market-data-service as of #90; this
 * consumer role is itself temporary too, standing in for the Gateway until
 * #94 decommissions the monolith and WS termination moves there for good.
 */
@Service
public class MarketDataService {

    private static final Set<String> KNOWN_SYMBOLS = Set.of("EUR/USD", "GBP/USD", "USD/JPY");

    private final EventBus eventBus;

    public MarketDataService(EventBus eventBus) {
        this.eventBus = eventBus;
    }

    public Set<String> symbols() {
        return KNOWN_SYMBOLS;
    }

    @Bean
    public Consumer<com.sdp.contracts.PriceTick> priceTickConsumer() {
        return tick -> eventBus.publish(new PriceTick(tick.symbol(), tick.bid(), tick.ask(), tick.timestamp()));
    }
}

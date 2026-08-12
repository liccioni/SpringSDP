package com.sdp.market;

import com.sdp.common.PriceTick;
import com.sdp.eventbus.EventBus;

import java.util.function.Consumer;

import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Service;

/**
 * The monolith's temporary consumer of PRICE_TICK messages from the Market
 * Data Service's RabbitMQ fanout exchange (see ADR 0022) - relays each one
 * onto the in-process EventBus so SdpWebSocketHandler's existing
 * subscription-filtered delivery to browsers is unchanged.
 *
 * Price generation itself moved to market-data-service as of #90; this
 * consumer role is itself temporary too, standing in for the Gateway until
 * #94 decommissions the monolith and WS termination moves there for good.
 * Known-symbol validation (used to live here, for TradeService) moved to
 * trading-service's own TradeService as of #92, alongside the rest of
 * trade validation - see ADR 0022's update.
 */
@Service
public class MarketDataService {

    private final EventBus eventBus;

    public MarketDataService(EventBus eventBus) {
        this.eventBus = eventBus;
    }

    @Bean
    public Consumer<com.sdp.contracts.PriceTick> priceTickConsumer() {
        return tick -> eventBus.publish(new PriceTick(tick.symbol(), tick.bid(), tick.ask(), tick.timestamp()));
    }
}

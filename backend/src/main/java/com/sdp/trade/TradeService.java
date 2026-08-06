package com.sdp.trade;

import com.sdp.common.Trade;
import com.sdp.eventbus.EventBus;
import com.sdp.market.MarketDataService;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;

/**
 * Handles CREATE_TRADE requests: validates them, then either creates a Trade
 * and stores it in in-memory state, or rejects it with a reason. Publishes
 * both outcomes to the EventBus for the WebSocket layer to broadcast. Does
 * not generate prices. Returns Mono<Trade> rather than a plain value so
 * callers compose it into a reactive pipeline without changes once trade
 * creation involves real I/O (e.g. persistence in MVP 0.4).
 */
@Service
public class TradeService {

    private final MarketDataService marketDataService;
    private final EventBus eventBus;
    private final List<Trade> blotter = new CopyOnWriteArrayList<>();

    public TradeService(MarketDataService marketDataService, EventBus eventBus) {
        this.marketDataService = marketDataService;
        this.eventBus = eventBus;
    }

    public Mono<Trade> createTrade(TradeRequest request) {
        Optional<String> rejectionReason = validate(request);
        if (rejectionReason.isPresent()) {
            publishRejection(request, rejectionReason.get());
            return Mono.empty();
        }
        return Mono.just(executeTrade(request));
    }

    private Trade executeTrade(TradeRequest request) {
        Trade trade = new Trade(
                UUID.randomUUID().toString(),
                request.symbol(),
                request.side(),
                request.price(),
                request.quantity(),
                Instant.now());

        blotter.add(trade);
        eventBus.publish(trade);
        return trade;
    }

    private void publishRejection(TradeRequest request, String reason) {
        eventBus.publish(new TradeRejected(request.symbol(), request.side(), request.price(), request.quantity(), reason));
    }

    private Optional<String> validate(TradeRequest request) {
        if (request.quantity().signum() <= 0) {
            return Optional.of("quantity must be greater than zero");
        }
        if (!marketDataService.symbols().contains(request.symbol())) {
            return Optional.of("unknown symbol: " + request.symbol());
        }
        return Optional.empty();
    }

    public List<Trade> blotter() {
        return List.copyOf(blotter);
    }
}

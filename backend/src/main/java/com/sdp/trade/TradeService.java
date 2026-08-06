package com.sdp.trade;

import com.sdp.common.Trade;
import com.sdp.market.MarketDataService;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.util.concurrent.Queues;

/**
 * Handles CREATE_TRADE requests: validates them, then either creates a Trade
 * and stores it in in-memory state, or rejects it with a reason. Emits both
 * outcomes for the WebSocket layer to broadcast as TRADE_CREATED /
 * TRADE_REJECTED. Does not generate prices.
 */
@Service
public class TradeService {

    private final MarketDataService marketDataService;
    private final List<Trade> blotter = new CopyOnWriteArrayList<>();

    // autoCancel=false: these sinks outlive any single WebSocket connection, so they must
    // not terminate just because the last subscriber (a disconnecting client) cancels.
    private final Sinks.Many<Trade> tradeCreated = Sinks.many().multicast().onBackpressureBuffer(Queues.SMALL_BUFFER_SIZE, false);
    private final Sinks.Many<TradeRejected> tradeRejected = Sinks.many().multicast().onBackpressureBuffer(Queues.SMALL_BUFFER_SIZE, false);

    public TradeService(MarketDataService marketDataService) {
        this.marketDataService = marketDataService;
    }

    public Optional<Trade> createTrade(TradeRequest request) {
        Optional<String> rejectionReason = validate(request);
        if (rejectionReason.isPresent()) {
            tradeRejected.tryEmitNext(new TradeRejected(
                    request.symbol(), request.side(), request.price(), request.quantity(), rejectionReason.get()));
            return Optional.empty();
        }

        Trade trade = new Trade(
                UUID.randomUUID().toString(),
                request.symbol(),
                request.side(),
                request.price(),
                request.quantity(),
                Instant.now());

        blotter.add(trade);
        tradeCreated.tryEmitNext(trade);
        return Optional.of(trade);
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

    public Flux<Trade> tradeCreated() {
        return tradeCreated.asFlux();
    }

    public Flux<TradeRejected> tradeRejected() {
        return tradeRejected.asFlux();
    }
}

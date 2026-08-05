package com.sdp.trade;

import com.sdp.common.Trade;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.util.concurrent.Queues;

/**
 * Handles CREATE_TRADE requests: creates a Trade, stores it in in-memory
 * state, and emits it for the WebSocket layer to broadcast as TRADE_CREATED.
 * Does not generate prices.
 */
@Service
public class TradeService {

    private final List<Trade> blotter = new CopyOnWriteArrayList<>();

    // autoCancel=false: this sink outlives any single WebSocket connection, so it must
    // not terminate just because the last subscriber (a disconnecting client) cancels.
    private final Sinks.Many<Trade> tradeCreated = Sinks.many().multicast().onBackpressureBuffer(Queues.SMALL_BUFFER_SIZE, false);

    public Trade createTrade(TradeRequest request) {
        Trade trade = new Trade(
                UUID.randomUUID().toString(),
                request.symbol(),
                request.side(),
                request.price(),
                request.quantity(),
                Instant.now());

        blotter.add(trade);
        tradeCreated.tryEmitNext(trade);
        return trade;
    }

    public List<Trade> blotter() {
        return List.copyOf(blotter);
    }

    public Flux<Trade> tradeCreated() {
        return tradeCreated.asFlux();
    }
}

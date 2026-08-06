package com.sdp.eventbus;

import org.springframework.stereotype.Component;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.util.concurrent.Queues;

/**
 * In-process pub/sub for domain events, decoupling publishers (MarketDataService,
 * TradeService) from subscribers (the WebSocket layer). Publishers don't know who,
 * if anyone, is listening. Backed by Reactor Sinks for now; the publish/subscribe
 * shape is what would carry over to a future external broker (Kafka, Redis Pub/Sub).
 */
@Component
public class EventBus {

    // autoCancel=false: this sink outlives any single subscriber, so it must not
    // terminate just because the last one (e.g. a disconnecting WebSocket) cancels.
    private final Sinks.Many<DomainEvent> events = Sinks.many().multicast().onBackpressureBuffer(Queues.SMALL_BUFFER_SIZE, false);

    // Sinks.Many requires the caller to serialize emissions: MarketDataService
    // publishes from its own ticker thread while TradeService publishes from
    // whichever WebSocket connection thread handled a CREATE_TRADE, so concurrent
    // publish() calls are the normal case, not an edge case. Without this lock,
    // tryEmitNext detects the concurrent access and silently drops the event
    // instead of delivering it.
    private final Object emitLock = new Object();

    public void publish(DomainEvent event) {
        synchronized (emitLock) {
            events.tryEmitNext(event);
        }
    }

    public Flux<DomainEvent> events() {
        return events.asFlux();
    }
}

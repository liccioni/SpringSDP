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

    public void publish(DomainEvent event) {
        events.tryEmitNext(event);
    }

    public Flux<DomainEvent> events() {
        return events.asFlux();
    }
}

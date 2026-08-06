package com.sdp.eventbus;

/**
 * A published event: something that already happened in a domain service
 * (a price tick, a trade created, a trade rejected). eventType() is the
 * WebSocket protocol's envelope type for this event.
 */
public interface DomainEvent {
    String eventType();
}

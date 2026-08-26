package com.sdp.eventbus;

/**
 * A DomainEvent that belongs to a specific submitting session rather than
 * being genuinely shared data (see PriceTick/SymbolSubscription for the
 * contrast). SdpWebSocketHandler uses this to deliver an event only to the
 * session whose username matches submittedBy(), instead of broadcasting it
 * to every connection - see ADR 0028.
 */
public interface Attributable {
    String submittedBy();
}

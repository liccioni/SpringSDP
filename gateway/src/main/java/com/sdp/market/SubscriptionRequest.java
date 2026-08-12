package com.sdp.market;

/**
 * Payload of a SUBSCRIBE or UNSUBSCRIBE envelope: the symbol whose price
 * stream a WebSocket connection wants to start or stop receiving.
 */
public record SubscriptionRequest(String symbol) {
}

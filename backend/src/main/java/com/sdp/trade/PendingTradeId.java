package com.sdp.trade;

/**
 * Payload of a CONFIRM_TRADE or CANCEL_TRADE envelope: the id of the
 * PendingTrade being resolved.
 */
public record PendingTradeId(String id) {
}

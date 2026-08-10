package com.sdp.contracts;

/**
 * Wire shape for CONFIRM_TRADE/CANCEL_TRADE requests: the id of the
 * PendingTrade being resolved.
 */
public record PendingTradeId(String id) {
}

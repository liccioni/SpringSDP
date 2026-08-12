package com.sdp.contracts;

/**
 * Reply half of the "trade-requests"/"trade-responses" correlated pair
 * (see TradeCommand, ADR 0022's update). {@code type} is one of
 * TRADE_PENDING, TRADE_CANCELLED, TRADE_HISTORY, TRADE_REJECTED, or NOOP
 * (nothing to report - e.g. an unknown/already-resolved pending trade id,
 * mirroring the wire protocol's own "silent no-op" for that case).
 */
public record TradeCommandResult(String correlationId, String type, Object payload) {
}

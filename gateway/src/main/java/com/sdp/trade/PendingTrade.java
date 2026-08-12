package com.sdp.trade;

import com.sdp.common.Side;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A validated CREATE_TRADE request held for the submitting connection's own
 * confirmation, before it becomes a real Trade. Not persisted, not a
 * DomainEvent - TRADE_PENDING is a targeted reply to the submitter, never
 * broadcast via EventBus. See ADR 0018.
 */
public record PendingTrade(String id, String symbol, Side side, BigDecimal price, BigDecimal quantity, Instant timestamp) {
}

package com.sdp.contracts;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Wire shape for a resolved trade: published by the Backend/Trading Service
 * once a CONFIRM_TRADE resolves, both as a correlated TradeCommandResult
 * reply payload (for the submitting session) and on the "trade-created"
 * fanout exchange, which the Gateway relays to blotter-subscribed sessions
 * (see ADR 0028).
 */
public record Trade(String id, String symbol, Side side, BigDecimal price, BigDecimal quantity, Instant timestamp) {
}

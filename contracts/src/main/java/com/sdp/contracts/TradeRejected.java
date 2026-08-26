package com.sdp.contracts;

import java.math.BigDecimal;

/**
 * Wire shape for a rejected trade: published by the Backend/Trading Service
 * as a correlated TradeCommandResult reply payload to the session whose
 * CREATE_TRADE was rejected. Never broadcast - a rejected trade is never
 * persisted, so there's nothing for any other session to see (ADR 0028).
 */
public record TradeRejected(String symbol, Side side, BigDecimal price, BigDecimal quantity, String reason) {
}

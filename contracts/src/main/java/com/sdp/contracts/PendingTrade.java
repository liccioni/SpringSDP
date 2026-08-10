package com.sdp.contracts;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Wire shape for a TRADE_PENDING reply: the Backend/Trading Service's reply
 * half of the CREATE_TRADE request-reply pair, routed back to the
 * submitting connection via the Gateway.
 */
public record PendingTrade(String id, String symbol, Side side, BigDecimal price, BigDecimal quantity, Instant timestamp) {
}

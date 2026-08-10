package com.sdp.contracts;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Wire shape for a TRADE_CREATED broadcast: published by the Backend/Trading
 * Service on a fanout exchange once a CONFIRM_TRADE resolves, consumed by
 * the Gateway for broadcast to every connected session.
 */
public record Trade(String id, String symbol, Side side, BigDecimal price, BigDecimal quantity, Instant timestamp) {
}

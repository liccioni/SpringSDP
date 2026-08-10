package com.sdp.contracts;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Wire shape for a PRICE_TICK: published by the Market Data Service,
 * consumed by the Gateway for broadcast to subscribed connections.
 */
public record PriceTick(String symbol, BigDecimal bid, BigDecimal ask, Instant timestamp) {
}

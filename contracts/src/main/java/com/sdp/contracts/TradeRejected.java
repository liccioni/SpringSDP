package com.sdp.contracts;

import java.math.BigDecimal;

/**
 * Wire shape for a TRADE_REJECTED broadcast: published by the Backend/Trading
 * Service on a fanout exchange, consumed by the Gateway for broadcast to
 * every connected session.
 */
public record TradeRejected(String symbol, Side side, BigDecimal price, BigDecimal quantity, String reason) {
}

package com.sdp.contracts;

import java.math.BigDecimal;

/**
 * Wire shape for a CREATE_TRADE request: published by the Gateway, consumed
 * by the Backend/Trading Service as the request half of the CREATE_TRADE /
 * TRADE_PENDING correlated request-reply pair.
 */
public record TradeRequest(String symbol, Side side, BigDecimal price, BigDecimal quantity) {
}

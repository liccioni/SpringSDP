package com.sdp.contracts;

import java.math.BigDecimal;

/**
 * Wire shape for a TRADE_REJECTED event: published by the Backend/Trading
 * Service on a fanout exchange, consumed by the Gateway for delivery to the
 * submitting session only (issue #152, ADR 0028) - {@code submittedBy} is
 * the username threaded through from the CREATE_TRADE command's own
 * {@code TradeCommand.submittedBy()}.
 */
public record TradeRejected(String symbol, Side side, BigDecimal price, BigDecimal quantity, String reason, String submittedBy) {
}

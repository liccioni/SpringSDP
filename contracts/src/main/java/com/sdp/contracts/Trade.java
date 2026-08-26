package com.sdp.contracts;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Wire shape for a TRADE_CREATED event: published by the Backend/Trading
 * Service on a fanout exchange once a CONFIRM_TRADE resolves, consumed by
 * the Gateway for delivery to the submitting session only (issue #152,
 * ADR 0028) - {@code submittedBy} is the username threaded through from the
 * CONFIRM_TRADE command's own {@code TradeCommand.submittedBy()}.
 *
 * Also reused as the row shape for TRADE_HISTORY (see ADR 0026):
 * {@code submittedBy} is {@code null} there, since submitter identity isn't
 * persisted on the {@code trades} table itself (deliberately out of scope
 * for #152 - see ADR 0028).
 */
public record Trade(String id, String symbol, Side side, BigDecimal price, BigDecimal quantity, Instant timestamp, String submittedBy) {
}

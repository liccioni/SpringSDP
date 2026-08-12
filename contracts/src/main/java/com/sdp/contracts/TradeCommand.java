package com.sdp.contracts;

import java.util.Set;

/**
 * Wire shape for the "trade-requests" correlated request/reply pair (see
 * ADR 0022's update): the Gateway (today, the monolith - see the ADR)
 * publishes one of these per CREATE_TRADE/CONFIRM_TRADE/CANCEL_TRADE/
 * GET_TRADE_HISTORY, and the Backend/Trading Service echoes {@code
 * correlationId} back on a TradeCommandResult so the reply can be routed
 * to the specific connection that asked. Mirrors the WS envelope's own
 * {@code {type, payload}} shape (com.sdp.websocket.Envelope in the
 * monolith), plus the correlation id and the submitting username (needed
 * for audit trail - there is no Session concept on this side of the wire).
 *
 * {@code roles} carries the submitter's Keycloak realm roles across the
 * wire so trading-service can authorize CREATE_TRADE itself - see ADR 0025.
 */
public record TradeCommand(String correlationId, String submittedBy, Set<String> roles, String type, Object payload) {
}

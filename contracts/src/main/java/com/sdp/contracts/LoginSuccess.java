package com.sdp.contracts;

/**
 * Wire shape for a LOGIN_SUCCESS audit event: published by the Gateway
 * once Keycloak's authorization-code exchange completes, consumed by the
 * Backend/Trading Service to persist via its own AuditService. Mirrors
 * SessionStarted's fire-and-forget shape (see ADR 0022's update for issue
 * #94) - sessionId is always null at this point in the flow (no Session
 * exists yet, per ADR 0017), so it isn't part of the wire shape at all.
 */
public record LoginSuccess(String username) {
}

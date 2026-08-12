package com.sdp.contracts;

/**
 * Wire shape for a LOGIN_ERROR audit event: published by the Gateway when
 * Keycloak's authorization-code exchange fails, consumed by the
 * Backend/Trading Service to persist via its own AuditService. Mirrors
 * SessionStarted's fire-and-forget shape (see ADR 0022's update for issue
 * #94). {@code username} is always "unknown" here - authentication never
 * completed, so no real username was ever resolved.
 */
public record LoginError(String username, String detail) {
}

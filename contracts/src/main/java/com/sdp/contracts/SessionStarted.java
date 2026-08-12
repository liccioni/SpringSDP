package com.sdp.contracts;

/**
 * Wire shape for a SESSION_STARTED audit event: published by the Gateway
 * (today, the monolith - see ADR 0022's update) once a connection's
 * identity is resolved, consumed by the Backend/Trading Service to persist
 * via its own AuditService.
 */
public record SessionStarted(String sessionId, String username) {
}

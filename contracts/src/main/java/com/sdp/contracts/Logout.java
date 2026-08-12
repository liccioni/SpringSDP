package com.sdp.contracts;

/**
 * Wire shape for a LOGOUT audit event: published by the Gateway once
 * Spring Security's logout completes, before redirecting to Keycloak's
 * end-session endpoint (issue #102). Mirrors LoginSuccess/LoginError's
 * fire-and-forget shape - sessionId isn't part of the wire shape for the
 * same reason it isn't on those: the security layer has no access to the
 * app's own connection-scoped Session (ADR 0017), only the Authentication
 * that was in the security context.
 */
public record Logout(String username) {
}

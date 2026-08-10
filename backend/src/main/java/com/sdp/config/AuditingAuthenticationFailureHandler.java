package com.sdp.config;

import com.sdp.audit.AuditService;

import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.server.WebFilterExchange;
import org.springframework.security.web.server.authentication.RedirectServerAuthenticationFailureHandler;
import org.springframework.security.web.server.authentication.ServerAuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

/**
 * Records LOGIN_ERROR - a deliberate narrowing of the old LOGIN_FAILURE
 * (ADR 0019, ADR 0020, issue #87): once Keycloak owns the actual credential
 * check, a raw failed password attempt is no longer observable here at
 * all - only whether the OAuth2 callback itself failed (e.g. consent
 * denied, code exchange failed). No username is available at this point in
 * the flow, since authentication never completed, so "unknown" is recorded
 * rather than leaving the audit_events table's NOT NULL username column
 * unset. Keeps the same /login?error redirect oauth2Login used by default
 * before this handler was introduced.
 */
@Component
public class AuditingAuthenticationFailureHandler implements ServerAuthenticationFailureHandler {

    private static final int MAX_DETAIL_LENGTH = 256;

    private final AuditService auditService;
    private final ServerAuthenticationFailureHandler redirectHandler = new RedirectServerAuthenticationFailureHandler("/login?error");

    public AuditingAuthenticationFailureHandler(AuditService auditService) {
        this.auditService = auditService;
    }

    @Override
    public Mono<Void> onAuthenticationFailure(WebFilterExchange webFilterExchange, AuthenticationException exception) {
        String detail = truncate(exception.getMessage() != null ? exception.getMessage() : exception.getClass().getSimpleName());
        return auditService.record(null, "unknown", "LOGIN_ERROR", detail)
                .then(redirectHandler.onAuthenticationFailure(webFilterExchange, exception));
    }

    private static String truncate(String detail) {
        return detail.length() <= MAX_DETAIL_LENGTH ? detail : detail.substring(0, MAX_DETAIL_LENGTH);
    }
}

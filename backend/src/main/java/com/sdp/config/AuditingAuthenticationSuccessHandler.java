package com.sdp.config;

import com.sdp.audit.AuditService;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.server.WebFilterExchange;
import org.springframework.security.web.server.authentication.RedirectServerAuthenticationSuccessHandler;
import org.springframework.security.web.server.authentication.ServerAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

/**
 * Records LOGIN_SUCCESS (ADR 0019, ADR 0020, issue #87) before performing
 * the same redirect-to-frontend behavior SecurityConfig used to build
 * inline. No Session (ADR 0017) exists yet at this point in the flow, so
 * sessionId stays null - the same convention the deleted AuthService.login()
 * used for its own LOGIN_SUCCESS event.
 */
@Component
public class AuditingAuthenticationSuccessHandler implements ServerAuthenticationSuccessHandler {

    private final AuditService auditService;
    private final ServerAuthenticationSuccessHandler redirectHandler;

    public AuditingAuthenticationSuccessHandler(AuditService auditService, @Value("${app.frontend-origin}") String frontendOrigin) {
        this.auditService = auditService;
        this.redirectHandler = new RedirectServerAuthenticationSuccessHandler(frontendOrigin);
    }

    @Override
    public Mono<Void> onAuthenticationSuccess(WebFilterExchange webFilterExchange, Authentication authentication) {
        return auditService.record(null, authentication.getName(), "LOGIN_SUCCESS", "")
                .then(redirectHandler.onAuthenticationSuccess(webFilterExchange, authentication));
    }
}

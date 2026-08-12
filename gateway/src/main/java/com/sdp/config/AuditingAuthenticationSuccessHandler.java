package com.sdp.config;

import com.sdp.contracts.LoginSuccess;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.server.WebFilterExchange;
import org.springframework.security.web.server.authentication.RedirectServerAuthenticationSuccessHandler;
import org.springframework.security.web.server.authentication.ServerAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

/**
 * Publishes LOGIN_SUCCESS over RabbitMQ (ADR 0019, ADR 0020, issue #87;
 * issue #94's update to ADR 0022 - fire-and-forget, mirroring
 * SESSION_STARTED, since this service has no Postgres access of its own)
 * for the Backend/Trading Service to audit, before performing the same
 * redirect-to-frontend behavior SecurityConfig used to build inline. No
 * Session (ADR 0017) exists yet at this point in the flow, so sessionId
 * isn't part of the wire shape at all - the same convention the deleted
 * AuthService.login() used for its own LOGIN_SUCCESS event.
 */
@Component
public class AuditingAuthenticationSuccessHandler implements ServerAuthenticationSuccessHandler {

    private static final String LOGIN_SUCCESS_BINDING = "loginSuccess-out-0";

    private final StreamBridge streamBridge;
    private final ServerAuthenticationSuccessHandler redirectHandler;

    public AuditingAuthenticationSuccessHandler(StreamBridge streamBridge, @Value("${app.frontend-origin}") String frontendOrigin) {
        this.streamBridge = streamBridge;
        this.redirectHandler = new RedirectServerAuthenticationSuccessHandler(frontendOrigin);
    }

    @Override
    public Mono<Void> onAuthenticationSuccess(WebFilterExchange webFilterExchange, Authentication authentication) {
        return Mono.fromRunnable(() -> streamBridge.send(LOGIN_SUCCESS_BINDING, new LoginSuccess(authentication.getName())))
                .then(redirectHandler.onAuthenticationSuccess(webFilterExchange, authentication));
    }
}

package com.sdp.config;

import com.sdp.contracts.LoginError;

import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.server.WebFilterExchange;
import org.springframework.security.web.server.authentication.RedirectServerAuthenticationFailureHandler;
import org.springframework.security.web.server.authentication.ServerAuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

/**
 * Publishes LOGIN_ERROR over RabbitMQ (issue #94's update to ADR 0022 -
 * fire-and-forget, mirroring SESSION_STARTED, since this service has no
 * Postgres access of its own) for the Backend/Trading Service to audit - a
 * deliberate narrowing of the old LOGIN_FAILURE (ADR 0019, ADR 0020, issue
 * #87): once Keycloak owns the actual credential check, a raw failed
 * password attempt is no longer observable here at all - only whether the
 * OAuth2 callback itself failed (e.g. consent denied, code exchange
 * failed). No username is available at this point in the flow, since
 * authentication never completed, so "unknown" is sent rather than leaving
 * the eventual audit_events row's NOT NULL username column unset. Keeps
 * the same /login?error redirect oauth2Login used by default before this
 * handler was introduced.
 */
@Component
public class AuditingAuthenticationFailureHandler implements ServerAuthenticationFailureHandler {

    private static final String LOGIN_ERROR_BINDING = "loginError-out-0";
    private static final int MAX_DETAIL_LENGTH = 256;

    private final StreamBridge streamBridge;
    private final ServerAuthenticationFailureHandler redirectHandler = new RedirectServerAuthenticationFailureHandler("/login?error");

    public AuditingAuthenticationFailureHandler(StreamBridge streamBridge) {
        this.streamBridge = streamBridge;
    }

    @Override
    public Mono<Void> onAuthenticationFailure(WebFilterExchange webFilterExchange, AuthenticationException exception) {
        String detail = truncate(exception.getMessage() != null ? exception.getMessage() : exception.getClass().getSimpleName());
        return Mono.fromRunnable(() -> streamBridge.send(LOGIN_ERROR_BINDING, new LoginError("unknown", detail)))
                .then(redirectHandler.onAuthenticationFailure(webFilterExchange, exception));
    }

    private static String truncate(String detail) {
        return detail.length() <= MAX_DETAIL_LENGTH ? detail : detail.substring(0, MAX_DETAIL_LENGTH);
    }
}

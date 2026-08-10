package com.sdp.config;

import java.time.Instant;

import com.sdp.audit.AuditEvent;
import com.sdp.audit.AuditService;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.web.server.WebFilterExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditingAuthenticationFailureHandlerTest {

    private final AuditService auditService = mock(AuditService.class);
    private final AuditingAuthenticationFailureHandler handler = new AuditingAuthenticationFailureHandler(auditService);

    @Test
    void recordsLoginErrorWithNoUsernameThenRedirectsToLoginError() {
        when(auditService.record(isNull(), eq("unknown"), eq("LOGIN_ERROR"), eq("consent_required")))
                .thenReturn(Mono.just(new AuditEvent("id", null, "unknown", "LOGIN_ERROR", "consent_required", Instant.now())));

        ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/login/oauth2/code/keycloak"));
        WebFilterChain chain = mock(WebFilterChain.class);
        AuthenticationException exception =
                new OAuth2AuthenticationException(new OAuth2Error("consent_required"), "consent_required");

        StepVerifier.create(handler.onAuthenticationFailure(new WebFilterExchange(exchange, chain), exception))
                .verifyComplete();

        verify(auditService).record(null, "unknown", "LOGIN_ERROR", "consent_required");
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(exchange.getResponse().getHeaders().getLocation()).hasToString("/login?error");
    }

    @Test
    void fallsBackToTheExceptionTypeWhenNoMessageIsAvailable() {
        when(auditService.record(isNull(), eq("unknown"), eq("LOGIN_ERROR"), any())).thenReturn(Mono.empty());

        ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/login/oauth2/code/keycloak"));
        WebFilterChain chain = mock(WebFilterChain.class);
        AuthenticationException exception = new AuthenticationException(null) {
        };

        StepVerifier.create(handler.onAuthenticationFailure(new WebFilterExchange(exchange, chain), exception))
                .verifyComplete();

        verify(auditService).record(null, "unknown", "LOGIN_ERROR", exception.getClass().getSimpleName());
    }

    @Test
    void truncatesAnOverlongDetailToFitTheAuditColumn() {
        when(auditService.record(isNull(), eq("unknown"), eq("LOGIN_ERROR"), any())).thenReturn(Mono.empty());

        ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/login/oauth2/code/keycloak"));
        WebFilterChain chain = mock(WebFilterChain.class);
        AuthenticationException exception = new OAuth2AuthenticationException(new OAuth2Error("invalid_token"), "x".repeat(500));

        StepVerifier.create(handler.onAuthenticationFailure(new WebFilterExchange(exchange, chain), exception))
                .verifyComplete();

        verify(auditService).record(eq(null), eq("unknown"), eq("LOGIN_ERROR"), argThat(detail -> detail.length() == 256));
    }
}

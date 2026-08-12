package com.sdp.config;

import com.sdp.contracts.LoginError;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.web.server.WebFilterExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;

import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AuditingAuthenticationFailureHandlerTest {

    private final StreamBridge streamBridge = mock(StreamBridge.class);
    private final AuditingAuthenticationFailureHandler handler = new AuditingAuthenticationFailureHandler(streamBridge);

    @Test
    void publishesLoginErrorWithNoUsernameThenRedirectsToLoginError() {
        ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/login/oauth2/code/keycloak"));
        WebFilterChain chain = mock(WebFilterChain.class);
        AuthenticationException exception =
                new OAuth2AuthenticationException(new OAuth2Error("consent_required"), "consent_required");

        StepVerifier.create(handler.onAuthenticationFailure(new WebFilterExchange(exchange, chain), exception))
                .verifyComplete();

        verify(streamBridge).send("loginError-out-0", new LoginError("unknown", "consent_required"));
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(exchange.getResponse().getHeaders().getLocation()).hasToString("/login?error");
    }

    @Test
    void fallsBackToTheExceptionTypeWhenNoMessageIsAvailable() {
        ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/login/oauth2/code/keycloak"));
        WebFilterChain chain = mock(WebFilterChain.class);
        AuthenticationException exception = new AuthenticationException(null) {
        };

        StepVerifier.create(handler.onAuthenticationFailure(new WebFilterExchange(exchange, chain), exception))
                .verifyComplete();

        verify(streamBridge).send("loginError-out-0", new LoginError("unknown", exception.getClass().getSimpleName()));
    }

    @Test
    void truncatesAnOverlongDetailToFitTheAuditColumn() {
        ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/login/oauth2/code/keycloak"));
        WebFilterChain chain = mock(WebFilterChain.class);
        AuthenticationException exception = new OAuth2AuthenticationException(new OAuth2Error("invalid_token"), "x".repeat(500));

        StepVerifier.create(handler.onAuthenticationFailure(new WebFilterExchange(exchange, chain), exception))
                .verifyComplete();

        verify(streamBridge).send(eq("loginError-out-0"), argThat(payload -> ((LoginError) payload).detail().length() == 256));
    }
}

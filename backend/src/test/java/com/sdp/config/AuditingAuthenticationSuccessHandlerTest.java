package com.sdp.config;

import java.time.Instant;
import java.util.List;

import com.sdp.audit.AuditEvent;
import com.sdp.audit.AuditService;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.server.WebFilterExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditingAuthenticationSuccessHandlerTest {

    private final AuditService auditService = mock(AuditService.class);
    private final AuditingAuthenticationSuccessHandler handler =
            new AuditingAuthenticationSuccessHandler(auditService, "http://localhost:5173");

    @Test
    void recordsLoginSuccessWithNoSessionIdThenRedirectsToTheFrontendOrigin() {
        when(auditService.record(isNull(), eq("trader1"), eq("LOGIN_SUCCESS"), eq("")))
                .thenReturn(Mono.just(new AuditEvent("id", null, "trader1", "LOGIN_SUCCESS", "", Instant.now())));

        ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/login/oauth2/code/keycloak"));
        WebFilterChain chain = mock(WebFilterChain.class);
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                "trader1", null, List.of(new SimpleGrantedAuthority("trader")));

        StepVerifier.create(handler.onAuthenticationSuccess(new WebFilterExchange(exchange, chain), authentication))
                .verifyComplete();

        verify(auditService).record(null, "trader1", "LOGIN_SUCCESS", "");
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(exchange.getResponse().getHeaders().getLocation()).hasToString("http://localhost:5173");
    }

    @Test
    void doesNotRedirectUntilTheAuditEventIsRecorded() {
        when(auditService.record(any(), any(), any(), any())).thenReturn(Mono.empty());

        ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/login/oauth2/code/keycloak"));
        WebFilterChain chain = mock(WebFilterChain.class);
        Authentication authentication = new UsernamePasswordAuthenticationToken("trader2", null, List.of());

        StepVerifier.create(handler.onAuthenticationSuccess(new WebFilterExchange(exchange, chain), authentication))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FOUND);
    }
}

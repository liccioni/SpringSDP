package com.sdp.config;

import java.util.List;

import com.sdp.contracts.LoginSuccess;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.server.WebFilterExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;

import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AuditingAuthenticationSuccessHandlerTest {

    private final StreamBridge streamBridge = mock(StreamBridge.class);
    private final AuditingAuthenticationSuccessHandler handler =
            new AuditingAuthenticationSuccessHandler(streamBridge, "http://localhost:5173");

    @Test
    void publishesLoginSuccessThenRedirectsToTheFrontendOrigin() {
        ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/login/oauth2/code/keycloak"));
        WebFilterChain chain = mock(WebFilterChain.class);
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                "trader1", null, List.of(new SimpleGrantedAuthority("trader")));

        StepVerifier.create(handler.onAuthenticationSuccess(new WebFilterExchange(exchange, chain), authentication))
                .verifyComplete();

        verify(streamBridge).send("loginSuccess-out-0", new LoginSuccess("trader1"));
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(exchange.getResponse().getHeaders().getLocation()).hasToString("http://localhost:5173");
    }

    @Test
    void redirectsEvenWithNoGrantedAuthorities() {
        ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/login/oauth2/code/keycloak"));
        WebFilterChain chain = mock(WebFilterChain.class);
        Authentication authentication = new UsernamePasswordAuthenticationToken("trader2", null, List.of());

        StepVerifier.create(handler.onAuthenticationSuccess(new WebFilterExchange(exchange, chain), authentication))
                .verifyComplete();

        verify(streamBridge).send("loginSuccess-out-0", new LoginSuccess("trader2"));
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FOUND);
    }
}

package com.sdp.config;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.sdp.contracts.Logout;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.server.WebFilterExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;

import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AuditingLogoutSuccessHandlerTest {

    private final StreamBridge streamBridge = mock(StreamBridge.class);
    private final AuditingLogoutSuccessHandler handler = new AuditingLogoutSuccessHandler(
            streamBridge,
            "http://localhost:8081/realms/sdp/protocol/openid-connect/logout",
            "http://localhost:5173",
            "sdp-backend");

    private Authentication oidcAuthentication(String username) {
        Instant issuedAt = Instant.now();
        OidcIdToken idToken = new OidcIdToken(
                "id-token-" + username, issuedAt, issuedAt.plusSeconds(300), Map.of("sub", username));
        OidcUser oidcUser = new DefaultOidcUser(List.of(new SimpleGrantedAuthority("trader")), idToken, "sub");
        return new OAuth2AuthenticationToken(oidcUser, oidcUser.getAuthorities(), "keycloak");
    }

    @Test
    void publishesLogoutThenRedirectsToKeycloaksEndSessionEndpoint() {
        ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/logout"));
        WebFilterChain chain = mock(WebFilterChain.class);
        Authentication authentication = oidcAuthentication("trader1");

        StepVerifier.create(handler.onLogoutSuccess(new WebFilterExchange(exchange, chain), authentication))
                .verifyComplete();

        verify(streamBridge).send("logout-out-0", new Logout("trader1"));
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FOUND);

        String location = exchange.getResponse().getHeaders().getLocation().toString();
        assertThat(location).startsWith("http://localhost:8081/realms/sdp/protocol/openid-connect/logout");
        assertThat(location).contains("id_token_hint=id-token-trader1");
        assertThat(location).contains("post_logout_redirect_uri=http://localhost:5173");
        assertThat(location).contains("client_id=sdp-backend");
    }

    @Test
    void publishesLogoutForADifferentUser() {
        ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/logout"));
        WebFilterChain chain = mock(WebFilterChain.class);
        Authentication authentication = oidcAuthentication("trader2");

        StepVerifier.create(handler.onLogoutSuccess(new WebFilterExchange(exchange, chain), authentication))
                .verifyComplete();

        verify(streamBridge).send("logout-out-0", new Logout("trader2"));
    }

    @Test
    void redirectsToFrontendOriginWhenSessionAlreadyExpired() {
        ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/logout"));
        WebFilterChain chain = mock(WebFilterChain.class);
        Authentication authentication = new AnonymousAuthenticationToken(
                "key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));

        StepVerifier.create(handler.onLogoutSuccess(new WebFilterExchange(exchange, chain), authentication))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(exchange.getResponse().getHeaders().getLocation().toString())
                .isEqualTo("http://localhost:5173");
    }
}

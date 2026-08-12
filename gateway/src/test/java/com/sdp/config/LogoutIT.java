package com.sdp.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.sdp.RabbitMqIntegrationTest;
import com.sdp.RedisIntegrationTest;
import com.sdp.contracts.Logout;

import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.session.ReactiveSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.data.redis.ReactiveRedisSessionRepository;
import org.springframework.web.reactive.function.client.WebClient;

// Authenticates with a real OidcUser principal (unlike
// SdpWebSocketHandlerIT's plain UsernamePasswordAuthenticationToken) since
// AuditingLogoutSuccessHandler needs a real OidcIdToken to build
// id_token_hint - see that class's javadoc for why the cast is safe in
// production. Proves the real Redis-session + CSRF + RabbitMQ wiring; the
// actual Keycloak end-session round trip is verified live (browser-driven),
// per the same standard SdpWebSocketHandlerIT sets for the login side.
//
// Uses a plain WebClient (no ClientHttpConnector redirect-following
// configured, so a 302 comes back to us directly, unlike a browser) rather
// than WebTestClient - this project's Spring Boot 4.1.0 doesn't split out a
// dedicated reactive-web-test-client autoconfiguration module the way
// earlier versions did.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("integration")
class LogoutIT implements RedisIntegrationTest, RabbitMqIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private ReactiveRedisSessionRepository sessionRepository;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @Autowired
    private ObjectMapper objectMapper;

    private WebClient webClient;
    private String logoutQueue;

    @BeforeEach
    void createWebClient() {
        webClient = WebClient.builder().baseUrl("http://localhost:" + port).build();
    }

    // Same non-auto-delete/exclusive queue treatment as
    // SdpWebSocketHandlerIT's sessionStartedQueue - see docs/testing.md's
    // autoDelete-queue-vanishes-between-receives gotcha.
    @BeforeEach
    void bindLogoutQueue() {
        FanoutExchange exchange = new FanoutExchange("logout");
        amqpAdmin.declareExchange(exchange);
        logoutQueue = amqpAdmin.declareQueue(new Queue("", false, true, false));
        amqpAdmin.declareBinding(BindingBuilder.bind(new Queue(logoutQueue)).to(exchange));
    }

    @AfterEach
    void deleteLogoutQueue() {
        amqpAdmin.deleteQueue(logoutQueue);
    }

    @Test
    void logoutRecordsAnAuditEventAndRedirectsToKeycloaksEndSessionEndpoint() throws Exception {
        String sessionId = createAuthenticatedOidcSession(sessionRepository, "trader1");
        String csrfToken = fetchCsrfToken(sessionId);

        String location = webClient.post().uri("/logout")
                .cookie("SESSION", sessionId)
                .cookie("XSRF-TOKEN", csrfToken)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .bodyValue("_csrf=" + csrfToken)
                .exchangeToMono(response -> {
                    assertThat(response.statusCode()).isEqualTo(HttpStatus.FOUND);
                    return response.releaseBody().thenReturn(response.headers().asHttpHeaders().getLocation().toString());
                })
                .block(Duration.ofSeconds(5));

        assertThat(location).startsWith("http://localhost:8081/realms/sdp/protocol/openid-connect/logout");
        assertThat(location).contains("id_token_hint=");
        assertThat(location).contains("post_logout_redirect_uri=http://localhost:5173");
        assertThat(location).contains("client_id=sdp-backend");

        Message message = rabbitTemplate.receive(logoutQueue, 5000);
        assertThat(message).isNotNull();
        Logout event = objectMapper.readValue(message.getBody(), Logout.class);
        assertThat(event.username()).isEqualTo("trader1");
    }

    // GET is a "safe" method the CSRF filter never validates, but
    // CsrfCookieWebFilter still forces the token Mono to be subscribed to,
    // writing the XSRF-TOKEN cookie the frontend's hidden logout form (and
    // this test) reads back - same bootstrap request an Angular-style SPA
    // would make before its first unsafe request.
    private String fetchCsrfToken(String sessionId) {
        return webClient.get().uri("/")
                .cookie("SESSION", sessionId)
                .exchangeToMono(response -> response.releaseBody()
                        .thenReturn(response.cookies().getFirst("XSRF-TOKEN").getValue()))
                .block(Duration.ofSeconds(5));
    }

    private <S extends Session> String createAuthenticatedOidcSession(ReactiveSessionRepository<S> repository, String username) {
        Authentication authentication = oidcAuthentication(username);
        return repository.createSession()
                .flatMap(session -> {
                    session.setAttribute("SPRING_SECURITY_CONTEXT", new SecurityContextImpl(authentication));
                    return repository.save(session).thenReturn(session.getId());
                })
                .block(Duration.ofSeconds(5));
    }

    private Authentication oidcAuthentication(String username) {
        Instant issuedAt = Instant.now();
        OidcIdToken idToken = new OidcIdToken(
                "id-token-" + username, issuedAt, issuedAt.plusSeconds(300), Map.of("sub", username));
        OidcUser oidcUser = new DefaultOidcUser(List.of(new SimpleGrantedAuthority("trader")), idToken, "sub");
        return new OAuth2AuthenticationToken(oidcUser, oidcUser.getAuthorities(), "keycloak");
    }
}

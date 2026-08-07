package com.sdp.auth;

import com.sdp.audit.AuditService;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.reactive.server.WebTestClient;

import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LoginControllerTest {

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

    private final AuthProperties properties = new AuthProperties(
            List.of(new DemoUser("trader1", ENCODER.encode("trader1pass"))));
    private final AuditService auditService = mock(AuditService.class);
    private final AuthService authService = new AuthService(properties, auditService);
    private final WebTestClient client = WebTestClient.bindToController(new LoginController(authService)).build();

    {
        when(auditService.record(any(), any(), any(), any())).thenReturn(Mono.empty());
    }

    @Test
    void returnsATokenForValidCredentials() {
        LoginResponse response = client.post().uri("/login")
                .bodyValue(new LoginRequest("trader1", "trader1pass"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(LoginResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(response).isNotNull();
        assertThat(response.token()).isNotBlank();
    }

    @Test
    void rejectsAnUnknownUsername() {
        client.post().uri("/login")
                .bodyValue(new LoginRequest("nobody", "trader1pass"))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void rejectsAWrongPassword() {
        client.post().uri("/login")
                .bodyValue(new LoginRequest("trader1", "wrong"))
                .exchange()
                .expectStatus().isUnauthorized();
    }
}

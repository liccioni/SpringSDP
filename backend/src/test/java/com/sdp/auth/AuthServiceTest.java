package com.sdp.auth;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

class AuthServiceTest {

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

    private final AuthProperties properties = new AuthProperties(
            List.of(new DemoUser("trader1", ENCODER.encode("trader1pass"))));
    private final AuthService service = new AuthService(properties);

    @Test
    void issuesATokenForValidCredentials() {
        StepVerifier.create(service.login("trader1", "trader1pass"))
                .assertNext(token -> assertThat(token).isNotBlank())
                .verifyComplete();
    }

    @Test
    void resolvesTheUsernameForAnIssuedToken() {
        String token = service.login("trader1", "trader1pass").block();

        assertThat(service.username(token)).contains("trader1");
    }

    @Test
    void rejectsAnUnknownUsername() {
        StepVerifier.create(service.login("nobody", "trader1pass"))
                .verifyComplete();
    }

    @Test
    void rejectsAWrongPassword() {
        StepVerifier.create(service.login("trader1", "wrong"))
                .verifyComplete();
    }

    @Test
    void doesNotResolveAnUnknownToken() {
        assertThat(service.username("not-a-real-token")).isEmpty();
    }

    @Test
    void eachLoginGetsAUniqueToken() {
        String first = service.login("trader1", "trader1pass").block();
        String second = service.login("trader1", "trader1pass").block();

        assertThat(first).isNotEqualTo(second);
    }
}

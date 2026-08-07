package com.sdp.auth;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;

/**
 * Checks credentials against the config-based demo user list and issues an
 * opaque token on success. Tokens live in memory only - see ADR 0016 - so
 * they don't survive an application restart.
 */
@Service
public class AuthService {

    private final Map<String, String> passwordHashesByUsername;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final Map<String, String> usernamesByToken = new ConcurrentHashMap<>();

    public AuthService(AuthProperties properties) {
        this.passwordHashesByUsername = properties.users().stream()
                .collect(Collectors.toMap(DemoUser::username, DemoUser::passwordHash));
    }

    public Mono<String> login(String username, String password) {
        String hash = passwordHashesByUsername.get(username);
        if (hash == null || !passwordEncoder.matches(password, hash)) {
            return Mono.empty();
        }
        String token = UUID.randomUUID().toString();
        usernamesByToken.put(token, username);
        return Mono.just(token);
    }

    public Optional<String> username(String token) {
        return Optional.ofNullable(usernamesByToken.get(token));
    }
}

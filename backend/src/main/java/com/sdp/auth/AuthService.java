package com.sdp.auth;

import com.sdp.audit.AuditService;

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
 * they don't survive an application restart. Records LOGIN_SUCCESS/
 * LOGIN_FAILURE audit events - see ADR 0019.
 */
@Service
public class AuthService {

    private final Map<String, String> passwordHashesByUsername;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final Map<String, String> usernamesByToken = new ConcurrentHashMap<>();
    private final AuditService auditService;

    public AuthService(AuthProperties properties, AuditService auditService) {
        this.passwordHashesByUsername = properties.users().stream()
                .collect(Collectors.toMap(DemoUser::username, DemoUser::passwordHash));
        this.auditService = auditService;
    }

    public Mono<String> login(String username, String password) {
        String hash = passwordHashesByUsername.get(username);
        if (hash == null || !passwordEncoder.matches(password, hash)) {
            return auditService.record(null, username, "LOGIN_FAILURE", "invalid credentials").then(Mono.empty());
        }
        String token = UUID.randomUUID().toString();
        usernamesByToken.put(token, username);
        return auditService.record(null, username, "LOGIN_SUCCESS", "").thenReturn(token);
    }

    public Optional<String> username(String token) {
        return Optional.ofNullable(usernamesByToken.get(token));
    }
}

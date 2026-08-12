package com.sdp.audit;

import com.sdp.contracts.LoginError;
import com.sdp.contracts.LoginSuccess;
import com.sdp.contracts.Logout;
import com.sdp.contracts.SessionStarted;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;

import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;

/**
 * Persists audit events for trading activity - see ADR 0019. Mirrors the
 * monolith's own AuditService (same table, same shape); moved here as of
 * #91 (see ADR 0022's update) since outbound trade outcomes are now this
 * service's responsibility. Backend-only for now: nothing reads this back
 * yet.
 */
@Service
public class AuditService {

    private final AuditEventRepository auditEventRepository;

    public AuditService(AuditEventRepository auditEventRepository) {
        this.auditEventRepository = auditEventRepository;
    }

    public Mono<AuditEvent> record(String sessionId, String username, String eventType, String detail) {
        return auditEventRepository.save(new AuditEvent(
                UUID.randomUUID().toString(), sessionId, username, eventType, detail, Instant.now()));
    }

    // The Gateway (issue #93's ADR 0022 update) publishes this once a
    // connection's identity is resolved; this service's own
    // AuditService.record is the only thing that changes how SESSION_STARTED
    // reaches Postgres, not what gets recorded. Same
    // blocking-on-listener-thread reasoning as TradeService's
    // tradeRequestConsumer(): the binder's own message-listener thread, not
    // a reactive HTTP/WS thread, so blocking here for proper at-least-once
    // semantics is unremarkable.
    @Bean
    public Consumer<SessionStarted> sessionStartedConsumer() {
        return event -> record(event.sessionId(), event.username(), "SESSION_STARTED", "").block(Duration.ofSeconds(10));
    }

    // The Gateway (issue #94's ADR 0022 update) publishes these from its
    // own OAuth2 login success/failure handlers - the last two events that
    // used to reach Postgres via a direct in-process AuditService call in
    // the monolith, now that the Gateway itself has no database access.
    // sessionId is always null for both: no Session (ADR 0017) exists yet
    // at login time, matching this table's existing convention.
    @Bean
    public Consumer<LoginSuccess> loginSuccessConsumer() {
        return event -> record(null, event.username(), "LOGIN_SUCCESS", "").block(Duration.ofSeconds(10));
    }

    @Bean
    public Consumer<LoginError> loginErrorConsumer() {
        return event -> record(null, event.username(), "LOGIN_ERROR", event.detail()).block(Duration.ofSeconds(10));
    }

    // The Gateway (issue #102's ADR 0023) publishes this once
    // AuditingLogoutSuccessHandler's onLogoutSuccess runs, before
    // redirecting the browser to Keycloak's end-session endpoint. sessionId
    // is null for the same reason it is on LOGIN_SUCCESS/LOGIN_ERROR: the
    // security layer has no access to the app's own connection-scoped
    // Session (ADR 0017).
    @Bean
    public Consumer<Logout> logoutConsumer() {
        return event -> record(null, event.username(), "LOGOUT", "").block(Duration.ofSeconds(10));
    }
}

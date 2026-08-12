package com.sdp.audit;

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

    // The Gateway (today, the monolith - see ADR 0022's update for issue
    // #93) publishes this once a connection's identity is resolved; this
    // service's own AuditService.record is the only thing that changes how
    // SESSION_STARTED reaches Postgres, not what gets recorded. Same
    // blocking-on-listener-thread reasoning as TradeService's
    // tradeRequestConsumer(): the binder's own message-listener thread, not
    // a reactive HTTP/WS thread, so blocking here for proper at-least-once
    // semantics is unremarkable.
    @Bean
    public Consumer<SessionStarted> sessionStartedConsumer() {
        return event -> record(event.sessionId(), event.username(), "SESSION_STARTED", "").block(Duration.ofSeconds(10));
    }
}

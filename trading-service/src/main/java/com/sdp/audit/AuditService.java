package com.sdp.audit;

import java.time.Instant;
import java.util.UUID;

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
}

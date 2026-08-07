package com.sdp.audit;

import com.sdp.PostgresIntegrationTest;

import java.time.Duration;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

// Proves the round-trip actually works, not just that AuditEvent implements
// Persistable - see docs/testing.md's Persistable gotcha (Trade silently
// persisted nothing until this exact style of test caught it).
@SpringBootTest
@Tag("integration")
class AuditEventRepositoryIT implements PostgresIntegrationTest {

    @Autowired
    private AuditService auditService;

    @Autowired
    private AuditEventRepository auditEventRepository;

    @Test
    void savedAuditEventsCanBeReadBackById() {
        AuditEvent saved = auditService.record("connection-1", "trader1", "SESSION_STARTED", "").block(Duration.ofSeconds(5));

        AuditEvent found = auditEventRepository.findById(saved.id()).block(Duration.ofSeconds(5));

        assertThat(found).isNotNull();
        assertThat(found.sessionId()).isEqualTo("connection-1");
        assertThat(found.username()).isEqualTo("trader1");
        assertThat(found.eventType()).isEqualTo("SESSION_STARTED");
    }

    @Test
    void aNullSessionIdRoundTripsAsNull() {
        AuditEvent saved = auditService.record(null, "trader1", "LOGIN_FAILURE", "invalid credentials").block(Duration.ofSeconds(5));

        AuditEvent found = auditEventRepository.findById(saved.id()).block(Duration.ofSeconds(5));

        assertThat(found).isNotNull();
        assertThat(found.sessionId()).isNull();
    }
}

package com.sdp.audit;

import org.junit.jupiter.api.Test;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuditServiceTest {

    private final AuditEventRepository auditEventRepository = mock(AuditEventRepository.class);
    private final AuditService service = new AuditService(auditEventRepository);

    @Test
    void recordsAnAuditEventWithTheGivenFields() {
        when(auditEventRepository.save(any())).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(service.record("connection-1", "trader1", "SESSION_STARTED", ""))
                .assertNext(event -> {
                    assertThat(event.id()).isNotBlank();
                    assertThat(event.sessionId()).isEqualTo("connection-1");
                    assertThat(event.username()).isEqualTo("trader1");
                    assertThat(event.eventType()).isEqualTo("SESSION_STARTED");
                    assertThat(event.detail()).isEmpty();
                    assertThat(event.timestamp()).isNotNull();
                })
                .verifyComplete();
    }

    @Test
    void allowsANullSessionIdForLoginTimeEvents() {
        when(auditEventRepository.save(any())).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(service.record(null, "trader1", "LOGIN_FAILURE", "invalid credentials"))
                .assertNext(event -> assertThat(event.sessionId()).isNull())
                .verifyComplete();
    }

    @Test
    void eachRecordedEventGetsAUniqueId() {
        when(auditEventRepository.save(any())).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        AuditEvent first = service.record("connection-1", "trader1", "SESSION_STARTED", "").block();
        AuditEvent second = service.record("connection-1", "trader1", "SESSION_STARTED", "").block();

        assertThat(first.id()).isNotEqualTo(second.id());
    }
}

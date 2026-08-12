package com.sdp.audit;

import com.sdp.contracts.LoginError;
import com.sdp.contracts.LoginSuccess;
import com.sdp.contracts.Logout;
import com.sdp.contracts.SessionStarted;

import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import reactor.core.publisher.Mono;

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

        AuditEvent event = service.record(null, "trader1", "TRADE_EXECUTED", "BUY 1000000 EUR/USD @ 1.0850").block();

        assertThat(event.id()).isNotBlank();
        assertThat(event.sessionId()).isNull();
        assertThat(event.username()).isEqualTo("trader1");
        assertThat(event.eventType()).isEqualTo("TRADE_EXECUTED");
        assertThat(event.timestamp()).isNotNull();
    }

    @Test
    void sessionStartedConsumerPersistsTheEventsSessionIdAndUsername() {
        when(auditEventRepository.save(any())).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        Consumer<SessionStarted> consumer = service.sessionStartedConsumer();
        consumer.accept(new SessionStarted("connection-1", "trader1"));

        var captor = org.mockito.ArgumentCaptor.forClass(AuditEvent.class);
        org.mockito.Mockito.verify(auditEventRepository).save(captor.capture());
        AuditEvent saved = captor.getValue();
        assertThat(saved.sessionId()).isEqualTo("connection-1");
        assertThat(saved.username()).isEqualTo("trader1");
        assertThat(saved.eventType()).isEqualTo("SESSION_STARTED");
    }

    @Test
    void loginSuccessConsumerPersistsWithNoSessionId() {
        when(auditEventRepository.save(any())).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        service.loginSuccessConsumer().accept(new LoginSuccess("trader1"));

        var captor = org.mockito.ArgumentCaptor.forClass(AuditEvent.class);
        org.mockito.Mockito.verify(auditEventRepository).save(captor.capture());
        AuditEvent saved = captor.getValue();
        assertThat(saved.sessionId()).isNull();
        assertThat(saved.username()).isEqualTo("trader1");
        assertThat(saved.eventType()).isEqualTo("LOGIN_SUCCESS");
    }

    @Test
    void loginErrorConsumerPersistsWithTheEventsDetail() {
        when(auditEventRepository.save(any())).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        service.loginErrorConsumer().accept(new LoginError("unknown", "invalid_token"));

        var captor = org.mockito.ArgumentCaptor.forClass(AuditEvent.class);
        org.mockito.Mockito.verify(auditEventRepository).save(captor.capture());
        AuditEvent saved = captor.getValue();
        assertThat(saved.sessionId()).isNull();
        assertThat(saved.username()).isEqualTo("unknown");
        assertThat(saved.eventType()).isEqualTo("LOGIN_ERROR");
        assertThat(saved.detail()).isEqualTo("invalid_token");
    }

    @Test
    void logoutConsumerPersistsWithNoSessionId() {
        when(auditEventRepository.save(any())).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        service.logoutConsumer().accept(new Logout("trader1"));

        var captor = org.mockito.ArgumentCaptor.forClass(AuditEvent.class);
        org.mockito.Mockito.verify(auditEventRepository).save(captor.capture());
        AuditEvent saved = captor.getValue();
        assertThat(saved.sessionId()).isNull();
        assertThat(saved.username()).isEqualTo("trader1");
        assertThat(saved.eventType()).isEqualTo("LOGOUT");
    }
}

package com.sdp.trading;

import com.sdp.PostgresIntegrationTest;
import com.sdp.RabbitMqIntegrationTest;
import com.sdp.audit.AuditEvent;
import com.sdp.audit.AuditEventRepository;
import com.sdp.contracts.SessionStarted;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

// Proves the real end-to-end path (issue #93): a SessionStarted event
// published onto the "session-started" fanout exchange is picked up by
// this service's own sessionStartedConsumer (com.sdp.audit.AuditService)
// and persisted - the same round trip the Gateway/monolith triggers for
// real once a connection's identity is resolved. Lives in this package
// (com.sdp.trading), not com.sdp.audit where AuditService itself lives:
// @SpringBootTest with no explicit `classes` auto-detects a
// @SpringBootConfiguration by searching upwards from the test's own
// package, and TradingServiceApplication sits at com.sdp.trading, a
// sibling of com.sdp.audit rather than its parent (see docs/testing.md's
// sibling-package-scanning gotcha) - a bare @SpringBootTest in
// com.sdp.audit can't find it. Passing `classes = TradingServiceApplication`
// explicitly works around the discovery failure but builds a distinct
// ApplicationContext from every other IT in this module and was observed
// to race the shared Testcontainers RabbitMQ container out from under
// whichever IT class ran second, flaking with "Connection refused" -
// living alongside TradeServiceIT/TradingServiceApplicationTests instead
// keeps this test on the exact same auto-detected, cached context.
@SpringBootTest
@Tag("integration")
class AuditServiceIT implements PostgresIntegrationTest, RabbitMqIntegrationTest {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private AuditEventRepository auditEventRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void sessionStartedPublishedOnTheFanoutExchangeIsPersisted() {
        String sessionId = UUID.randomUUID().toString();
        String username = "trader-" + UUID.randomUUID();
        publish(new SessionStarted(sessionId, username));

        // Other tests in this class/run persist against the same shared
        // container/table - filter by this test's own unique username
        // rather than asserting on the full table.
        AuditEvent found = auditEventRepository.findAll()
                .filter(event -> username.equals(event.username()) && "SESSION_STARTED".equals(event.eventType()))
                .next()
                .repeatWhenEmpty(attempts -> attempts.delayElements(Duration.ofMillis(200)).take(25))
                .block(Duration.ofSeconds(10));

        assertThat(found).isNotNull();
        assertThat(found.sessionId()).isEqualTo(sessionId);
        assertThat(found.username()).isEqualTo(username);
    }

    private void publish(SessionStarted event) {
        byte[] body = objectMapper.writeValueAsBytes(event);
        MessageProperties properties = new MessageProperties();
        properties.setContentType("application/json");
        rabbitTemplate.send("session-started", "", new Message(body, properties));
    }
}

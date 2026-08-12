package com.sdp;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Gateway integration tests that need a real RabbitMQ broker implement
 * this interface, per the same real-infrastructure-via-Testcontainers
 * standard as trading-service's copy (ADR 0002/0014). Same digest as
 * docker-compose.yml's `rabbitmq` service, for consistency.
 *
 * Deliberately Testcontainers' "singleton container" pattern (a plain
 * static field, started once via the helper below) rather than
 * @Testcontainers/@Container - see trading-service's own
 * RabbitMqIntegrationTest javadoc for why (issue #93: the @Container
 * variant only shares a static container within one test class, not
 * across a whole run, once a module has more than one real IT class using
 * it).
 */
public interface RabbitMqIntegrationTest {

    @ServiceConnection
    RabbitMQContainer RABBITMQ = started(new RabbitMQContainer(
            DockerImageName.parse("rabbitmq@sha256:44bf7eb50fe1765885659e49ccfdc775f8e531964d979321aee380a071f49f94")
                    .asCompatibleSubstituteFor("rabbitmq")));

    static RabbitMQContainer started(RabbitMQContainer container) {
        container.start();
        return container;
    }
}

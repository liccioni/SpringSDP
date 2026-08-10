package com.sdp;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Mirrors backend's RabbitMqIntegrationTest (same digest) - a separate
 * copy, not a shared dependency, since trading-service is an independent
 * Gradle build (see ADR 0022).
 */
@Testcontainers
public interface RabbitMqIntegrationTest {

    @Container
    @ServiceConnection
    RabbitMQContainer RABBITMQ = new RabbitMQContainer(
            DockerImageName.parse("rabbitmq@sha256:44bf7eb50fe1765885659e49ccfdc775f8e531964d979321aee380a071f49f94")
                    .asCompatibleSubstituteFor("rabbitmq"));
}

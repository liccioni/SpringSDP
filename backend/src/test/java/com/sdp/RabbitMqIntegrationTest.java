package com.sdp;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Backend integration tests that need a real RabbitMQ broker (as of #90's
 * PRICE_TICK consumer, see ADR 0022) implement this interface, per the same
 * real-infrastructure-via-Testcontainers standard as PostgresIntegrationTest
 * (ADR 0002/0014). Same digest as docker-compose.yml's `rabbitmq` service,
 * for consistency.
 */
@Testcontainers
public interface RabbitMqIntegrationTest {

    @Container
    @ServiceConnection
    RabbitMQContainer RABBITMQ = new RabbitMQContainer(
            DockerImageName.parse("rabbitmq@sha256:44bf7eb50fe1765885659e49ccfdc775f8e531964d979321aee380a071f49f94")
                    .asCompatibleSubstituteFor("rabbitmq"));
}

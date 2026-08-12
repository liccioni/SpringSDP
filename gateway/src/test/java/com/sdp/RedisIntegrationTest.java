package com.sdp;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Gateway integration tests that need a real Redis-backed session
 * implement this interface, per the same real-infrastructure-via-
 * Testcontainers standard as PostgresIntegrationTest (ADR 0002/0014). No
 * dedicated Redis Testcontainers module exists, so @ServiceConnection
 * ("redis") is given explicitly on a GenericContainer.
 *
 * Deliberately Testcontainers' "singleton container" pattern, same
 * reasoning as this module's own RabbitMqIntegrationTest.
 */
public interface RedisIntegrationTest {

    @ServiceConnection("redis")
    GenericContainer<?> REDIS = started(new GenericContainer<>(
            DockerImageName.parse("redis@sha256:e7723ff73d963f5cc6d9c4643ea3d989527a402a319239054e9472a7fb9219a2"))
            .withExposedPorts(6379));

    static GenericContainer<?> started(GenericContainer<?> container) {
        container.start();
        return container;
    }
}

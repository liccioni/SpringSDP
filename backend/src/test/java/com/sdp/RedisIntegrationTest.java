package com.sdp;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Backend integration tests that need a real Redis-backed session implement
 * this interface, per the same real-infrastructure-via-Testcontainers
 * standard as PostgresIntegrationTest (ADR 0002/0014). No dedicated Redis
 * Testcontainers module exists, so @ServiceConnection("redis") is given
 * explicitly on a GenericContainer.
 */
@Testcontainers
public interface RedisIntegrationTest {

    @Container
    @ServiceConnection("redis")
    GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis@sha256:e7723ff73d963f5cc6d9c4643ea3d989527a402a319239054e9472a7fb9219a2"))
            .withExposedPorts(6379);
}

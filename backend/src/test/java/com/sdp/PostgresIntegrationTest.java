package com.sdp;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Backend integration tests that need a real database implement this
 * interface to get a shared Postgres container, per ADR 0002/0014: real
 * infrastructure via Testcontainers, not mocks. The container is a single
 * static instance shared by every implementing test class, started once for
 * the whole test run rather than once per class.
 */
@Testcontainers
public interface PostgresIntegrationTest {

    @Container
    @ServiceConnection
    PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgres@sha256:742f40ea20b9ff2ff31db5458d127452988a2164df9e17441e191f3b72252193")
                    .asCompatibleSubstituteFor("postgres"));
}

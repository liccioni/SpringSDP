package com.sdp;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Mirrors backend's PostgresIntegrationTest (same digest, same
 * shared-static-container pattern, ADR 0002/0014) - a separate copy, not a
 * shared dependency, since trading-service is an independent Gradle build
 * (see ADR 0022).
 */
@Testcontainers
public interface PostgresIntegrationTest {

    @Container
    @ServiceConnection
    PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgres@sha256:742f40ea20b9ff2ff31db5458d127452988a2164df9e17441e191f3b72252193")
                    .asCompatibleSubstituteFor("postgres"));
}

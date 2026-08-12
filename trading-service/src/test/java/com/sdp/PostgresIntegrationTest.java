package com.sdp;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Mirrors backend's PostgresIntegrationTest (same digest, ADR 0002/0014) -
 * a separate copy, not a shared dependency, since trading-service is an
 * independent Gradle build (see ADR 0022).
 *
 * Deliberately Testcontainers' "singleton container" pattern (a plain
 * static field, started once via the helper below) rather than
 * @Testcontainers/@Container - see RabbitMqIntegrationTest's javadoc for
 * why (issue #93 found the @Container variant only shares a static
 * container within one test class, not across the whole run).
 */
public interface PostgresIntegrationTest {

    @ServiceConnection
    PostgreSQLContainer POSTGRES = started(new PostgreSQLContainer(
            DockerImageName.parse("postgres@sha256:742f40ea20b9ff2ff31db5458d127452988a2164df9e17441e191f3b72252193")
                    .asCompatibleSubstituteFor("postgres")));

    static PostgreSQLContainer started(PostgreSQLContainer container) {
        container.start();
        return container;
    }
}

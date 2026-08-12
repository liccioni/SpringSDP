package com.sdp;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Mirrors backend's RabbitMqIntegrationTest (same digest) - a separate
 * copy, not a shared dependency, since trading-service is an independent
 * Gradle build (see ADR 0022).
 *
 * Deliberately NOT @Testcontainers/@Container-managed (found while adding
 * issue #93's AuditServiceIT, this module's second real Tag("integration")
 * class touching RabbitMQ - TradeServiceIT was previously the only one, so
 * this was invisible): that combination only shares a static container
 * *within one test class* - the JUnit5 Testcontainers extension stops a
 * static @Container field after the last test method of the class that
 * happens to run it, not at JVM shutdown. Whichever IT class ran second
 * then hit "Connection refused" - its Spring test context is cached and
 * shared with the first class (both resolve the same
 * @SpringBootConfiguration), so it kept the *first* container's now-dead
 * host:port rather than picking up the fresh one Testcontainers silently
 * created on the next .start() call. This is Testcontainers' own
 * documented "singleton container" pattern instead: started once (via the
 * static field initializer below) for the whole JVM, cleaned up by Ryuk at
 * JVM exit, never stopped mid-run.
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

package org.urizo.axmodulestudio.backend.product;

import java.nio.file.Path;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ax.product-runtime")
public record ProductRuntimeProperties(
        String jdbcUrl,
        Path databasePasswordFile,
        String valkeyHost,
        int valkeyPort,
        Path valkeyPasswordFile,
        String productQueue,
        String codingQueue,
        Duration outboxLease,
        Duration workerPoll,
        String workerId) {
}

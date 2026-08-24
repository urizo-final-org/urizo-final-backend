package org.urizo.axmodulestudio.backend.coding.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ax.coding.model-turn-database")
public record CodingModelTurnDatabaseProperties(
        String jdbcUrl,
        Path passwordFile,
        Duration leaseDuration) {

    public CodingModelTurnDatabaseProperties {
        jdbcUrl = Objects.requireNonNull(jdbcUrl, "jdbcUrl is required");
        passwordFile = Objects.requireNonNull(passwordFile, "passwordFile is required");
        leaseDuration = Objects.requireNonNull(leaseDuration, "leaseDuration is required");
        if (leaseDuration.compareTo(Duration.ofSeconds(5)) < 0
                || leaseDuration.compareTo(Duration.ofMinutes(2)) > 0) {
            throw new IllegalArgumentException("leaseDuration must be between 5 seconds and 2 minutes.");
        }
    }
}

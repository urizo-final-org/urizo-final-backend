package org.urizo.axmodulestudio.backend.coding.config;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ax.coding.job-database")
public record CodingJobLifecycleDatabaseProperties(
        String jdbcUrl,
        Path passwordFile) {
}

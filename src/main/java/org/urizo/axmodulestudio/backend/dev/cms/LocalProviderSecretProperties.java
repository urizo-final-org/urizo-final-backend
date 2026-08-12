package org.urizo.axmodulestudio.backend.dev.cms;

import java.nio.file.Path;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ax.local-provider-secrets")
public record LocalProviderSecretProperties(
        String jdbcUrl,
        Path databasePasswordFile,
        Path masterKeyFile) {

    public LocalProviderSecretProperties {
        jdbcUrl = Objects.requireNonNull(jdbcUrl, "jdbcUrl is required");
        databasePasswordFile = Objects.requireNonNull(databasePasswordFile, "databasePasswordFile is required");
        masterKeyFile = Objects.requireNonNull(masterKeyFile, "masterKeyFile is required");
    }
}

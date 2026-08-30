package org.urizo.axmodulestudio.backend.integration.ai.mcp;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ax.ai.mcp-platform")
public record McpPlatformProperties(
        URI endpoint,
        Path credentialFile,
        Duration connectTimeout,
        Duration requestTimeout,
        int maxResponseBytes) {

    private static final int MIN_RESPONSE_BYTES = 1_024;
    private static final int MAX_RESPONSE_BYTES = 1_048_576;

    public McpPlatformProperties {
        endpoint = Objects.requireNonNull(endpoint, "endpoint is required");
        credentialFile = Objects.requireNonNull(credentialFile, "credentialFile is required");
        connectTimeout = requireBoundedDuration(connectTimeout, "connectTimeout", Duration.ofSeconds(10));
        requestTimeout = requireBoundedDuration(requestTimeout, "requestTimeout", Duration.ofSeconds(30));

        String scheme = endpoint.getScheme();
        if (!endpoint.isAbsolute()
                || endpoint.getHost() == null
                || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                || !"/mcp".equals(endpoint.getPath())
                || endpoint.getRawQuery() != null
                || endpoint.getRawFragment() != null
                || endpoint.getUserInfo() != null) {
            throw new IllegalArgumentException("MCP endpoint must be an absolute HTTP(S) URI with the exact /mcp path.");
        }
        if (maxResponseBytes < MIN_RESPONSE_BYTES || maxResponseBytes > MAX_RESPONSE_BYTES) {
            throw new IllegalArgumentException("MCP maxResponseBytes is outside the approved range.");
        }
    }

    private static Duration requireBoundedDuration(Duration value, String name, Duration maximum) {
        Objects.requireNonNull(value, name + " is required");
        if (value.isZero() || value.isNegative() || value.compareTo(maximum) > 0) {
            throw new IllegalArgumentException("MCP " + name + " is outside the approved range.");
        }
        return value;
    }
}

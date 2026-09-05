package org.urizo.axmodulestudio.backend.integration.ai.observability;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ax.ai.langfuse")
public record LangfuseProperties(
        String baseUrl,
        String publicKey,
        String secretKey,
        Duration connectTimeout,
        Duration requestTimeout,
        Duration cacheTtl,
        int maxResponseBytes) {

    static final URI JAPAN_BASE_URI = URI.create("https://jp.cloud.langfuse.com");

    public LangfuseProperties {
        baseUrl = normalize(baseUrl);
        publicKey = normalize(publicKey);
        secretKey = normalize(secretKey);
        connectTimeout = bounded(connectTimeout, "connectTimeout", Duration.ofSeconds(10));
        requestTimeout = bounded(requestTimeout, "requestTimeout", Duration.ofSeconds(30));
        cacheTtl = bounded(cacheTtl, "cacheTtl", Duration.ofMinutes(5));
        if (maxResponseBytes < 1_024 || maxResponseBytes > 1_048_576) {
            throw new IllegalArgumentException("Langfuse maxResponseBytes is outside the approved range.");
        }
    }

    boolean configured() {
        return JAPAN_BASE_URI.toString().equals(withoutTrailingSlash(baseUrl))
                && !publicKey.isEmpty()
                && !secretKey.isEmpty();
    }

    URI endpoint(String pathAndQuery) {
        if (!configured() || pathAndQuery == null || !pathAndQuery.startsWith("/api/public/")) {
            throw new IllegalStateException("Langfuse is not configured for the approved endpoint.");
        }
        return URI.create(JAPAN_BASE_URI + pathAndQuery);
    }

    @Override
    public String toString() {
        return "LangfuseProperties[baseUrl=" + (baseUrl.isEmpty() ? "" : "REDACTED")
                + ", publicKey=REDACTED, secretKey=REDACTED"
                + ", connectTimeout=" + connectTimeout
                + ", requestTimeout=" + requestTimeout
                + ", cacheTtl=" + cacheTtl
                + ", maxResponseBytes=" + maxResponseBytes + "]";
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip();
    }

    private static String withoutTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static Duration bounded(Duration value, String field, Duration maximum) {
        Objects.requireNonNull(value, field + " is required");
        if (value.isZero() || value.isNegative() || value.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(
                    "Langfuse " + field + " is outside the approved range.");
        }
        return value;
    }
}

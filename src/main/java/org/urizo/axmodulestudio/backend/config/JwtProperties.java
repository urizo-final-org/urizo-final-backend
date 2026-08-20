package org.urizo.axmodulestudio.backend.config;

import java.nio.file.Path;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ax.auth.jwt")
public record JwtProperties(
        Duration accessTokenLifetime,
        Duration refreshTokenLifetime,
        String issuer,
        String accessAudience,
        String refreshAudience,
        Path signingKeyFile,
        boolean secureRefreshCookie) {

    public JwtProperties {
        accessTokenLifetime = positiveOr(accessTokenLifetime, Duration.ofMinutes(15), "access");
        refreshTokenLifetime = positiveOr(refreshTokenLifetime, Duration.ofDays(7), "refresh");
        issuer = textOr(issuer, "ax-module-studio");
        accessAudience = textOr(accessAudience, "axms-api");
        refreshAudience = textOr(refreshAudience, "axms-refresh");
        signingKeyFile = signingKeyFile == null
                ? Path.of("/run/secrets/auth_jwt_signing_key") : signingKeyFile;
        if (accessAudience.equals(refreshAudience)) {
            throw new IllegalArgumentException("Access and refresh JWT audiences must differ.");
        }
    }

    private static Duration positiveOr(Duration value, Duration fallback, String name) {
        Duration resolved = value == null ? fallback : value;
        if (resolved.isZero() || resolved.isNegative()) {
            throw new IllegalArgumentException(name + " JWT lifetime must be positive.");
        }
        return resolved;
    }

    private static String textOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}

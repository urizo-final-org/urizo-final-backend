package org.urizo.axmodulestudio.backend.common.auth;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Production authentication settings.
 *
 * @param sessionLifetime how long an issued session stays usable before it expires
 */
@ConfigurationProperties("ax.auth")
public record AuthProperties(Duration sessionLifetime) {

    static final Duration DEFAULT_SESSION_LIFETIME = Duration.ofHours(8);

    public AuthProperties {
        if (sessionLifetime == null) {
            sessionLifetime = DEFAULT_SESSION_LIFETIME;
        }
        if (sessionLifetime.isZero() || sessionLifetime.isNegative()) {
            throw new IllegalArgumentException("ax.auth.session-lifetime must be positive.");
        }
    }
}

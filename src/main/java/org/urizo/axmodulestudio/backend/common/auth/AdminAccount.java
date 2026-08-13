package org.urizo.axmodulestudio.backend.common.auth;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Persisted administrator identity.
 *
 * <p>The password hash never leaves the authentication package and is never serialized into a
 * response payload.
 */
public record AdminAccount(
        UUID accountId,
        String loginId,
        String passwordHash,
        AdminRole role,
        AccountStatus status,
        Instant createdAt) {

    public AdminAccount {
        Objects.requireNonNull(accountId, "accountId is required.");
        Objects.requireNonNull(role, "role is required.");
        Objects.requireNonNull(status, "status is required.");
        Objects.requireNonNull(createdAt, "createdAt is required.");
        if (loginId == null || loginId.isBlank()) {
            throw new IllegalArgumentException("loginId is required.");
        }
        if (passwordHash == null || passwordHash.isBlank()) {
            throw new IllegalArgumentException("passwordHash is required.");
        }
    }

    /** Whether this account may open a new session or keep an existing one. */
    public boolean canAuthenticate() {
        return status.canAuthenticate();
    }
}

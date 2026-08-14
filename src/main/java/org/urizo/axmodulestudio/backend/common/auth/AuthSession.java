package org.urizo.axmodulestudio.backend.common.auth;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Revocable opaque session issued on a successful login.
 *
 * <p>The record holds the stored token digest, never the presented token value. Refresh tokens and
 * device management are out of scope for the MVP.
 */
public record AuthSession(
        UUID sessionId,
        UUID accountId,
        String tokenDigest,
        Instant issuedAt,
        Instant expiresAt,
        Instant revokedAt) {

    public AuthSession {
        Objects.requireNonNull(sessionId, "sessionId is required.");
        Objects.requireNonNull(accountId, "accountId is required.");
        Objects.requireNonNull(issuedAt, "issuedAt is required.");
        Objects.requireNonNull(expiresAt, "expiresAt is required.");
        if (tokenDigest == null || tokenDigest.isBlank()) {
            throw new IllegalArgumentException("tokenDigest is required.");
        }
        if (!expiresAt.isAfter(issuedAt)) {
            throw new IllegalArgumentException("expiresAt must be after issuedAt.");
        }
    }

    /** A revoked or expired session fails closed. */
    public boolean isUsableAt(Instant now) {
        Objects.requireNonNull(now, "now is required.");
        return revokedAt == null && now.isBefore(expiresAt);
    }
}

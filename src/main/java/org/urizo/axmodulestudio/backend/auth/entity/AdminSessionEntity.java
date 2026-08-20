package org.urizo.axmodulestudio.backend.auth.entity;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** DB state for a refresh JWT. The raw JWT is never stored. */
@Entity
@Table(name = "admin_session", schema = "app")
public class AdminSessionEntity {

    @Id
    @Column(name = "session_id", nullable = false, updatable = false)
    private UUID sessionId;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Column(name = "jwt_id", nullable = false, updatable = false, unique = true)
    private UUID jwtId;

    @Column(name = "token_digest", nullable = false, updatable = false, length = 64, unique = true)
    private String tokenDigest;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private RefreshTokenStatus status;

    @Column(name = "rotated_at")
    private Instant rotatedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "replaced_by_jwt_id")
    private UUID replacedByJwtId;

    protected AdminSessionEntity() {
    }

    public AdminSessionEntity(
            UUID sessionId,
            UUID accountId,
            UUID jwtId,
            String tokenDigest,
            Instant issuedAt,
            Instant expiresAt) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId is required.");
        this.accountId = Objects.requireNonNull(accountId, "accountId is required.");
        this.jwtId = Objects.requireNonNull(jwtId, "jwtId is required.");
        if (tokenDigest == null || tokenDigest.isBlank()) {
            throw new IllegalArgumentException("tokenDigest is required.");
        }
        this.tokenDigest = tokenDigest;
        this.issuedAt = Objects.requireNonNull(issuedAt, "issuedAt is required.");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt is required.");
        if (!expiresAt.isAfter(issuedAt)) {
            throw new IllegalArgumentException("expiresAt must be after issuedAt.");
        }
        this.status = RefreshTokenStatus.ACTIVE;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public UUID getJwtId() {
        return jwtId;
    }

    public String getTokenDigest() {
        return tokenDigest;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public RefreshTokenStatus getStatus() {
        return status;
    }

    public boolean isUsableAt(Instant now) {
        return status == RefreshTokenStatus.ACTIVE && now.isBefore(expiresAt);
    }

    public void rotate(Instant at, UUID replacementJwtId) {
        if (status != RefreshTokenStatus.ACTIVE) {
            throw new IllegalStateException("Only an active refresh session can rotate.");
        }
        status = RefreshTokenStatus.ROTATED;
        rotatedAt = Objects.requireNonNull(at, "rotation time is required.");
        replacedByJwtId = Objects.requireNonNull(replacementJwtId, "replacement is required.");
    }

    public void revoke(Instant at) {
        if (status == RefreshTokenStatus.ACTIVE) {
            status = RefreshTokenStatus.REVOKED;
            revokedAt = Objects.requireNonNull(at, "revocation time is required.");
        }
    }
}

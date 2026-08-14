package org.urizo.axmodulestudio.backend.common.auth;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Administrator view that carries no credential material.
 *
 * <p>Account management returns this instead of {@link AdminAccount} so a password hash cannot reach
 * a response payload by accident.
 */
public record AdministratorSummary(
        UUID accountId,
        String loginId,
        AdminRole role,
        AccountStatus status,
        Instant createdAt) {

    public AdministratorSummary {
        Objects.requireNonNull(accountId, "accountId is required.");
        Objects.requireNonNull(loginId, "loginId is required.");
        Objects.requireNonNull(role, "role is required.");
        Objects.requireNonNull(status, "status is required.");
        Objects.requireNonNull(createdAt, "createdAt is required.");
    }

    static AdministratorSummary of(AdminAccount account) {
        return new AdministratorSummary(
                account.accountId(), account.loginId(), account.role(), account.status(),
                account.createdAt());
    }
}

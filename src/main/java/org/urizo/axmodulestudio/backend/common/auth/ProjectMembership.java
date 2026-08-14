package org.urizo.axmodulestudio.backend.common.auth;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Assignment of a {@code GENERAL_ADMIN} to one Project.
 *
 * <p>A {@code SUPER_ADMIN} reaches every Project through its platform-global scope and therefore
 * needs no membership row.
 */
public record ProjectMembership(
        UUID accountId,
        UUID projectId,
        Instant assignedAt) {

    public ProjectMembership {
        Objects.requireNonNull(accountId, "accountId is required.");
        Objects.requireNonNull(projectId, "projectId is required.");
        Objects.requireNonNull(assignedAt, "assignedAt is required.");
    }
}

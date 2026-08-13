package org.urizo.axmodulestudio.backend.common.auth;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Server-derived authority for one request.
 *
 * <p>Every field originates from the validated session and persisted membership. A client-supplied
 * actor id, role, or Project claim never contributes to this context.
 */
public record ActorContext(
        UUID actorId,
        AdminRole role,
        Set<UUID> assignedProjectIds) {

    public ActorContext {
        Objects.requireNonNull(actorId, "actorId is required.");
        Objects.requireNonNull(role, "role is required.");
        assignedProjectIds = Set.copyOf(
                Objects.requireNonNull(assignedProjectIds, "assignedProjectIds is required."));
    }

    /** A platform-global role reaches every Project; others need a persisted membership. */
    public boolean canAccessProject(UUID projectId) {
        Objects.requireNonNull(projectId, "projectId is required.");
        return role.isPlatformGlobal() || assignedProjectIds.contains(projectId);
    }

    /** Platform technical configuration stays in the delivery-company lane. */
    public boolean canConfigurePlatform() {
        return role.isPlatformGlobal();
    }
}

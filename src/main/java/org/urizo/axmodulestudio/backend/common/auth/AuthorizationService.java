package org.urizo.axmodulestudio.backend.common.auth;

import java.util.Objects;
import java.util.UUID;

/**
 * Single evaluation point for the permission matrix.
 *
 * <p>Feature Slices ask this service instead of repeating role comparisons, so a matrix change stays
 * in one place. Frontend menu hiding is a usability feature only; every protected operation repeats
 * the check here.
 */
public class AuthorizationService {

    /**
     * Enforces a platform-global permission.
     *
     * @throws AccessDeniedException when the actor is not in the delivery-company technical lane
     */
    public void authorize(ActorContext actor, AdminPermission permission) {
        Objects.requireNonNull(actor, "actor is required.");
        requireScope(permission, true);
        if (!actor.canConfigurePlatform()) {
            throw new AccessDeniedException(
                    "Platform configuration requires the delivery-company technical role.");
        }
    }

    /**
     * Enforces a Project-scoped permission against one target Project.
     *
     * @throws ProjectNotVisibleException when the Project is outside the actor's assignment, so the
     *     caller answers 404 instead of confirming that the Project exists
     */
    public void authorize(ActorContext actor, AdminPermission permission, UUID projectId) {
        Objects.requireNonNull(actor, "actor is required.");
        Objects.requireNonNull(projectId, "projectId is required.");
        requireScope(permission, false);
        if (!actor.canAccessProject(projectId)) {
            throw new ProjectNotVisibleException("The requested resource does not exist.");
        }
    }

    /** Non-throwing form used to build role-aware navigation. */
    public boolean permits(ActorContext actor, AdminPermission permission) {
        Objects.requireNonNull(actor, "actor is required.");
        Objects.requireNonNull(permission, "permission is required.");
        return permission.isPlatformGlobal()
                ? actor.canConfigurePlatform()
                : !actor.assignedProjectIds().isEmpty() || actor.canConfigurePlatform();
    }

    /** Non-throwing form for one known Project. */
    public boolean permits(ActorContext actor, AdminPermission permission, UUID projectId) {
        Objects.requireNonNull(actor, "actor is required.");
        Objects.requireNonNull(permission, "permission is required.");
        Objects.requireNonNull(projectId, "projectId is required.");
        return permission.isPlatformGlobal()
                ? actor.canConfigurePlatform()
                : actor.canAccessProject(projectId);
    }

    /** Guards against calling the wrong overload, which would silently skip the Project check. */
    private static void requireScope(AdminPermission permission, boolean platformGlobal) {
        Objects.requireNonNull(permission, "permission is required.");
        if (permission.isPlatformGlobal() != platformGlobal) {
            throw new IllegalArgumentException(
                    permission + " must be authorized through its "
                            + (permission.isPlatformGlobal() ? "platform-global" : "Project-scoped")
                            + " overload.");
        }
    }
}

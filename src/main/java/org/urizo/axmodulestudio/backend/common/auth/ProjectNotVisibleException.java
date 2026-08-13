package org.urizo.axmodulestudio.backend.common.auth;

/**
 * Raised when a resource belongs to a Project the actor is not assigned to.
 *
 * <p>The caller answers with {@code 404} rather than {@code 403} so an unauthorized actor cannot
 * confirm that a Project id exists.
 */
public class ProjectNotVisibleException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public static final String CODE = "RESOURCE_NOT_FOUND";

    public ProjectNotVisibleException(String message) {
        super(message);
    }
}

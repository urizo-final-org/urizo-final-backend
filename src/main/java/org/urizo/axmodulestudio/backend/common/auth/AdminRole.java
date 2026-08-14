package org.urizo.axmodulestudio.backend.common.auth;

import java.util.Locale;

/**
 * The two fixed administrator roles of the Auth/RBAC MVP.
 *
 * <p>Custom roles and a permission editor are out of scope. {@code PROJECT_ADMIN} survives only as a
 * legacy alias for the existing Coding consumer contract and is never persisted as a role value.
 */
public enum AdminRole {

    /** Delivery-company technical engineer with platform-global scope. */
    SUPER_ADMIN,

    /** Customer-company CMS operator limited to assigned Projects. */
    GENERAL_ADMIN;

    private static final String LEGACY_PROJECT_ADMIN = "PROJECT_ADMIN";

    /**
     * Resolves a persisted or legacy role string.
     *
     * @throws IllegalArgumentException when the value maps to no MVP role
     */
    public static AdminRole from(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Role value is required.");
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (LEGACY_PROJECT_ADMIN.equals(normalized)) {
            return GENERAL_ADMIN;
        }
        return AdminRole.valueOf(normalized);
    }

    /** Whether this role reaches every Project without a persisted membership. */
    public boolean isPlatformGlobal() {
        return this == SUPER_ADMIN;
    }
}

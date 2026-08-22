package org.urizo.axmodulestudio.backend.auth.entity;

import java.util.Locale;

/** Fixed administrator roles currently supported by the login boundary. */
public enum AdminRole {
    SUPER_ADMIN,
    GENERAL_ADMIN,
    GENERAL_USER;

    public static AdminRole from(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Role value is required.");
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return valueOf(normalized);
    }

    public boolean isPlatformGlobal() {
        return this == SUPER_ADMIN;
    }

    public boolean isCmsAdministrator() {
        return this == SUPER_ADMIN || this == GENERAL_ADMIN;
    }
}

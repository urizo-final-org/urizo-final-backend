package org.urizo.axmodulestudio.backend.common.auth;

import java.util.Locale;

/**
 * Administrator account lifecycle limited to {@code AXMS-FND-03}.
 *
 * <p>Invitation and customer member lifecycle states belong to {@code AXMS-CMS-01}.
 */
public enum AccountStatus {

    ACTIVE,

    DISABLED;

    public static AccountStatus from(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Account status is required.");
        }
        return AccountStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }

    /** Only an active account may authenticate or keep an existing session. */
    public boolean canAuthenticate() {
        return this == ACTIVE;
    }
}

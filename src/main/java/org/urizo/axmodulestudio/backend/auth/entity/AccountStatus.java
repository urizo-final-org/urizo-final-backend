package org.urizo.axmodulestudio.backend.auth.entity;

public enum AccountStatus {
    ACTIVE,
    DISABLED;

    public boolean canAuthenticate() {
        return this == ACTIVE;
    }
}

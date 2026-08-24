package org.urizo.axmodulestudio.backend.auth.security;

import java.util.Objects;
import java.util.UUID;

import org.urizo.axmodulestudio.backend.auth.entity.AdminRole;

/** Server-derived actor used as the authenticated principal details. */
public record AuthenticatedActor(UUID actorId, String name, AdminRole role) {
    public AuthenticatedActor {
        Objects.requireNonNull(actorId, "actorId is required.");
        Objects.requireNonNull(name, "name is required.");
        Objects.requireNonNull(role, "role is required.");
    }
}

package org.urizo.axmodulestudio.backend.common.auth;

import java.time.Instant;
import java.util.Objects;

/**
 * Result of resolving a presented session.
 *
 * <p>The expiry travels with the actor so a caller that must report it does not read the session a
 * second time.
 */
public record SessionIdentity(ActorContext actor, Instant expiresAt) {

    public SessionIdentity {
        Objects.requireNonNull(actor, "actor is required.");
        Objects.requireNonNull(expiresAt, "expiresAt is required.");
    }
}

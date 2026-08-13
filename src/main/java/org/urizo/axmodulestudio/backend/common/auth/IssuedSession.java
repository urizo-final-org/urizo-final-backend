package org.urizo.axmodulestudio.backend.common.auth;

import java.time.Instant;
import java.util.Objects;

/**
 * Result of a successful login.
 *
 * <p>The opaque token exists only in this response; persistence keeps its digest. The caller returns
 * it to the client once and never reads it back from storage.
 */
public record IssuedSession(String token, Instant expiresAt, ActorContext actor) {

    public IssuedSession {
        Objects.requireNonNull(expiresAt, "expiresAt is required.");
        Objects.requireNonNull(actor, "actor is required.");
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("token is required.");
        }
    }
}

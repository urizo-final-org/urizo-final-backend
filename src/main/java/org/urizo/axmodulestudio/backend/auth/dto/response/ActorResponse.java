package org.urizo.axmodulestudio.backend.auth.dto.response;

import java.util.UUID;

import org.urizo.axmodulestudio.backend.auth.entity.AdminRole;
import org.urizo.axmodulestudio.backend.security.AuthenticatedActor;

public record ActorResponse(UUID actorId, String name, AdminRole role) {
    public static ActorResponse from(AuthenticatedActor actor) {
        return new ActorResponse(actor.actorId(), actor.name(), actor.role());
    }
}

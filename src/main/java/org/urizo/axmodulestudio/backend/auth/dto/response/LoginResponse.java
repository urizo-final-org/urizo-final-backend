package org.urizo.axmodulestudio.backend.auth.dto.response;

import java.time.Instant;
import java.util.UUID;

public record LoginResponse(
        String schemaVersion,
        UUID traceId,
        String sessionToken,
        String tokenType,
        Instant expiresAt,
        ActorResponse actor) {
}

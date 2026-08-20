package org.urizo.axmodulestudio.backend.auth.dto.response;

import java.time.Instant;
import java.util.UUID;

public record CurrentSessionResponse(
        String schemaVersion,
        UUID traceId,
        ActorResponse actor,
        Instant expiresAt) {
}

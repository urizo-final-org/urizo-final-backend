package org.urizo.axmodulestudio.backend.auth.dto.response;

import java.util.UUID;

public record ErrorResponse(String schemaVersion, UUID traceId, ErrorDetail error) {
}

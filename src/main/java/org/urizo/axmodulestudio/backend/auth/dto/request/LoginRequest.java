package org.urizo.axmodulestudio.backend.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank String schemaVersion,
        @NotBlank @Size(max = 120) String loginId,
        @NotBlank @Size(min = 8, max = 256) String passwordValue) {

    public LoginRequest {
        if (!"1.0".equals(schemaVersion)) {
            throw new IllegalArgumentException("schemaVersion must be 1.0.");
        }
    }
}

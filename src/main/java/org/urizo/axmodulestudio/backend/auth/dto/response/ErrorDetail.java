package org.urizo.axmodulestudio.backend.auth.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

public record ErrorDetail(
        String code,
        String message,
        boolean retryable,
        @JsonInclude(JsonInclude.Include.NON_NULL) Long retryAfterMs) {
}

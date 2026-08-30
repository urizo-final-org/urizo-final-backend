package org.urizo.axmodulestudio.backend.orchestration.dto;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

public final class ProfileVersionContract {

    public static final String SCHEMA_VERSION = "1.0";
    public static final String EXECUTION_STATE_NOT_STARTED = "NOT_STARTED";

    private ProfileVersionContract() { }

    public record PreContextErrorEnvelope(
            String schemaVersion,
            UUID requestId,
            UUID traceId,
            ErrorDetail error) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorDetail(
            String code,
            String message,
            boolean retryable,
            Long retryAfterMs,
            String executionState) { }
}

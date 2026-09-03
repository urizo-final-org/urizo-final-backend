package org.urizo.axmodulestudio.backend.coding.dto;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public final class CodingRunnerContract {

    public static final String SCHEMA_VERSION = "1.0";

    public static final Set<String> KINDS = Set.of(
            "CREATE_WORKTREE",
            "PREPARE_SCAN_WORKTREE",
            "BUILD",
            "TEST",
            "PREVIEW_UP",
            "PREVIEW_DOWN",
            "CREATE_PR",
            "CHECK_DEV_MERGE",
            "DEPLOY_LOCAL_COMPOSE");

    private static final Set<String> OUTCOMES = Set.of(
            "SUCCEEDED", "RETRYABLE_FAILURE", "PERMANENT_FAILURE");

    private CodingRunnerContract() { }

    public record ClaimRequest(
            @NotBlank String schemaVersion,
            @NotNull UUID traceId,
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._:-]{2,63}$") String runnerId) {
        public ClaimRequest { requireVersion(schemaVersion); }
    }

    public record HeartbeatRequest(
            @NotBlank String schemaVersion,
            @NotNull UUID traceId,
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._:-]{2,63}$") String runnerId,
            @NotNull UUID taskId,
            @NotNull UUID leaseId) {
        public HeartbeatRequest { requireVersion(schemaVersion); }
    }

    public record OutcomeRequest(
            @NotBlank String schemaVersion,
            @NotNull UUID traceId,
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._:-]{2,63}$") String runnerId,
            @NotNull UUID taskId,
            @NotNull UUID leaseId,
            @NotBlank String outcome,
            JsonNode result,
            @Pattern(regexp = "^[A-Z][A-Z0-9_]{2,119}$") String errorCode) {
        public OutcomeRequest {
            requireVersion(schemaVersion);
            if (!OUTCOMES.contains(outcome)) {
                throw new IllegalArgumentException("outcome is unsupported.");
            }
            if (outcome.endsWith("FAILURE") != (errorCode != null)) {
                throw new IllegalArgumentException("errorCode is required only for a failure outcome.");
            }
        }
    }

    public record ClaimResponse(
            String schemaVersion,
            UUID traceId,
            UUID taskId,
            String kind,
            JsonNode payload,
            UUID leaseId,
            Instant leaseExpiresAt,
            int attempt,
            int maxAttempts) { }

    public record HeartbeatResponse(
            String schemaVersion,
            UUID traceId,
            UUID taskId,
            UUID leaseId,
            Instant leaseExpiresAt) { }

    public record OutcomeResponse(
            String schemaVersion,
            UUID traceId,
            UUID taskId,
            String status,
            int attempt) { }

    public record RunnerErrorEnvelope(
            String schemaVersion,
            UUID traceId,
            UUID taskId,
            @Valid ErrorDetail error) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorDetail(
            String code,
            String message,
            boolean retryable,
            Long retryAfterMs) { }

    private static void requireVersion(String version) {
        if (!SCHEMA_VERSION.equals(version)) {
            throw new IllegalArgumentException("schemaVersion must be 1.0.");
        }
    }
}

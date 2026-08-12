package org.urizo.axmodulestudio.backend.coding.worker;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public final class CodingWorkerContract {

    public static final String SCHEMA_VERSION = "1.0";
    private static final Set<String> OUTCOMES = Set.of(
            "WAITING_APPROVAL", "COMPLETED", "RETRYABLE_FAILURE", "PERMANENT_FAILURE");

    private CodingWorkerContract() { }

    public record ClaimRequest(
            @NotBlank String schemaVersion,
            @NotNull UUID eventId,
            @NotNull UUID jobId,
            @NotNull UUID traceId,
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._:-]{7,127}$") String idempotencyKey,
            @Min(1) int attempt,
            @Min(1) int expectedStateVersion) {
        public ClaimRequest { requireVersion(schemaVersion); }
    }

    public record HeartbeatRequest(
            @NotBlank String schemaVersion,
            @NotNull UUID jobId,
            @NotNull UUID traceId,
            @NotNull UUID leaseId,
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._:-]{7,127}$") String idempotencyKey,
            @Min(1) int expectedStateVersion) {
        public HeartbeatRequest { requireVersion(schemaVersion); }
    }

    public record OutcomeRequest(
            @NotBlank String schemaVersion,
            @NotNull UUID jobId,
            @NotNull UUID traceId,
            @NotNull UUID leaseId,
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._:-]{7,127}$") String idempotencyKey,
            @Min(1) int expectedStateVersion,
            @NotBlank String outcome,
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
            UUID jobId,
            UUID traceId,
            UUID leaseId,
            Instant leaseExpiresAt,
            int stateVersion,
            boolean resume,
            @Valid Snapshot snapshot) { }

    public record Snapshot(
            Actor actor,
            Project project,
            Repository repository,
            String graphStep,
            String baseSha,
            String contextDigest,
            String policyHash,
            String promptVersion,
            List<String> allowedCapabilities,
            List<String> allowedNodes,
            Instant deadlineAt,
            String systemPrompt,
            String userPrompt,
            String toolPath,
            UUID approvalId) {
        public Snapshot {
            allowedCapabilities = List.copyOf(allowedCapabilities);
            allowedNodes = List.copyOf(allowedNodes);
        }
    }

    public record Actor(UUID actorId, String role) { }
    public record Project(UUID projectId) { }
    public record Repository(UUID repositoryId) { }

    public record HeartbeatResponse(
            String schemaVersion,
            UUID jobId,
            UUID traceId,
            UUID leaseId,
            Instant leaseExpiresAt,
            int stateVersion) { }

    public record OutcomeResponse(
            String schemaVersion,
            UUID jobId,
            UUID traceId,
            int stateVersion,
            String status) { }

    public record JobErrorEnvelope(
            String schemaVersion,
            UUID traceId,
            UUID jobId,
            String idempotencyKey,
            @Valid ErrorDetail error) { }

    public record PreContextErrorEnvelope(
            String schemaVersion,
            UUID requestId,
            UUID traceId,
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

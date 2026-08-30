package org.urizo.axmodulestudio.backend.coding.dto;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class CodingJobLifecycleContract {

    public static final String SCHEMA_VERSION = "1.0";

    private static final Set<String> CAPABILITIES = Set.of(
            "CHAT", "STRUCTURED_OUTPUT", "TOOL_CALLING");

    private CodingJobLifecycleContract() {
    }

    public enum Status {
        PENDING,
        RUNNING,
        WAITING_APPROVAL,
        COMPLETED,
        FAILED,
        CANCELLED,
        EXPIRED;

        public boolean terminal() {
            return this == COMPLETED || this == FAILED || this == CANCELLED || this == EXPIRED;
        }
    }

    public record CreateRequest(
            @NotBlank String schemaVersion,
            @NotNull UUID profileVersionId,
            @NotNull UUID actorId,
            @NotNull UUID projectId,
            @NotNull UUID repositoryId,
            @NotBlank @Pattern(regexp = "^[a-z][a-z0-9_-]{0,119}$") String graphStep,
            @NotBlank @Pattern(regexp = "^(sha1:[0-9a-f]{40}|sha256:[0-9a-f]{64})$") String baseSha,
            @NotBlank @Pattern(regexp = "^sha256:[0-9a-f]{64}$") String contextDigest,
            @NotBlank @Pattern(regexp = "^sha256:[0-9a-f]{64}$") String policyHash,
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9._-]{1,120}$") String promptVersion,
            @NotEmpty @Size(max = 3) List<@NotBlank String> allowedCapabilities,
            @NotEmpty @Size(max = 50) List<@NotBlank @Pattern(
                    regexp = "^[a-z][a-z0-9_-]{0,119}$") String> allowedNodes,
            @NotNull Instant expiresAt) {

        public CreateRequest {
            requireVersion(schemaVersion);
            allowedCapabilities = immutableUnique(allowedCapabilities, "allowedCapabilities");
            allowedNodes = immutableUnique(allowedNodes, "allowedNodes");
            if (!CAPABILITIES.containsAll(allowedCapabilities)) {
                throw new IllegalArgumentException("allowedCapabilities contains an unsupported value.");
            }
            if (!allowedNodes.contains(graphStep)) {
                throw new IllegalArgumentException("graphStep must be present in allowedNodes.");
            }
        }

        @Override
        public String toString() {
            return "CreateRequest[schemaVersion=" + schemaVersion
                    + ", actorId=" + actorId
                    + ", projectId=" + projectId
                    + ", repositoryId=" + repositoryId
                    + ", graphStep=" + graphStep
                    + ", baseSha=REDACTED, contextDigest=REDACTED, policyHash=REDACTED"
                    + ", promptVersion=" + promptVersion
                    + ", allowedCapabilities=" + allowedCapabilities
                    + ", allowedNodes=" + allowedNodes
                    + ", expiresAt=" + expiresAt + "]";
        }
    }

    public record TransitionRequest(
            @NotBlank String schemaVersion,
            @Min(1) int expectedStateVersion,
            @NotNull Status targetStatus,
            @Valid Failure failure) {

        public TransitionRequest {
            requireVersion(schemaVersion);
            if ((targetStatus == Status.FAILED) != (failure != null)) {
                throw new IllegalArgumentException(
                        "failure is required only when targetStatus is FAILED.");
            }
        }
    }

    public record Failure(
            @NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{2,119}$") String code,
            boolean retryable) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record JobResponse(
            String schemaVersion,
            UUID jobId,
            UUID traceId,
            UUID profileVersionId,
            UUID actorId,
            UUID projectId,
            UUID repositoryId,
            String graphStep,
            Status status,
            int stateVersion,
            String promptVersion,
            List<String> allowedCapabilities,
            List<String> allowedNodes,
            Instant expiresAt,
            Instant createdAt,
            Instant startedAt,
            Instant updatedAt,
            Instant finishedAt,
            Failure failure) {

        public JobResponse {
            requireVersion(schemaVersion);
            allowedCapabilities = List.copyOf(allowedCapabilities);
            allowedNodes = List.copyOf(allowedNodes);
        }
    }

    public record SessionResponse(
            String schemaVersion,
            String csrfToken,
            boolean enabled,
            Instant checkedAt) {

        public SessionResponse {
            requireVersion(schemaVersion);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorEnvelope(
            String schemaVersion,
            UUID traceId,
            UUID jobId,
            ErrorDetail error) {

        public ErrorEnvelope {
            requireVersion(schemaVersion);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorDetail(
            String code,
            String message,
            boolean retryable,
            @Min(1) @Max(3_600_000) Long retryAfterMs) {
    }

    private static void requireVersion(String version) {
        if (!SCHEMA_VERSION.equals(version)) {
            throw new IllegalArgumentException("schemaVersion must be 1.0.");
        }
    }

    private static List<String> immutableUnique(List<String> values, String field) {
        Objects.requireNonNull(values, field + " is required");
        List<String> copy = List.copyOf(values);
        if (Set.copyOf(copy).size() != copy.size()) {
            throw new IllegalArgumentException(field + " must contain unique values.");
        }
        return copy;
    }
}

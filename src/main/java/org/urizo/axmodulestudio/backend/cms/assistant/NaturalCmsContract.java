package org.urizo.axmodulestudio.backend.cms.assistant;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class NaturalCmsContract {

    public static final String SCHEMA_VERSION = "1.0";
    private static final Pattern RESOURCE_ID = Pattern.compile(
            "^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$");
    private static final Pattern HANDLER_KEY = Pattern.compile(
            "^cms\\.(analyze|preview|discard|apply)$");
    private static final Pattern SHA256 = Pattern.compile("^sha256:[0-9a-f]{64}$");
    private static final Set<String> DECISIONS = Set.of("APPROVED", "REJECTED");

    private NaturalCmsContract() { }

    public record ResourceRef(@NotBlank String type, @NotBlank String id) {
        public ResourceRef {
            if (!"CONTENT".equals(type)
                    || id == null
                    || RESOURCE_ID.matcher(id).matches() == false) {
                throw new IllegalArgumentException("Natural CMS resource is invalid.");
            }
        }
    }

    public record CreateJobRequest(
            String schemaVersion,
            @NotNull UUID profileVersionId,
            @NotBlank @Size(max = 10_000) String requestText,
            @NotNull @Valid ResourceRef resource) {
        public CreateJobRequest {
            requireVersion(schemaVersion);
            requestText = requestText == null ? null : requestText.trim();
        }
    }

    public record ApprovalDecisionRequest(
            String schemaVersion,
            @NotNull UUID previewId,
            @NotBlank String previewHash,
            @NotBlank String decision,
            @Size(max = 2_000) String feedback) {
        public ApprovalDecisionRequest {
            requireVersion(schemaVersion);
            requireDigest(previewHash, "previewHash");
            if (!DECISIONS.contains(decision)
                    || ("REJECTED".equals(decision)
                        && (feedback == null || feedback.isBlank()))) {
                throw new IllegalArgumentException("Natural CMS approval decision is invalid.");
            }
            feedback = feedback == null ? null : feedback.trim();
        }
    }

    public record StageExecutionRequest(
            String schemaVersion,
            @NotNull UUID traceId,
            @NotNull UUID profileVersionId,
            int expectedStateVersion,
            int executionAttempt,
            @NotBlank String handlerKey,
            @NotNull UUID resultId) {
        public StageExecutionRequest {
            requireVersion(schemaVersion);
            if (expectedStateVersion < 1
                    || executionAttempt < 1
                    || handlerKey == null
                    || HANDLER_KEY.matcher(handlerKey).matches() == false) {
                throw new IllegalArgumentException("Natural CMS stage request is invalid.");
            }
        }
    }

    public record StageExecutionResponse(
            String schemaVersion,
            UUID resultId,
            String handlerKey,
            String resultPort,
            ResourceRef resource,
            JsonNode structuredCommand,
            UUID previewId,
            String previewHash,
            JsonNode payload) {
        public StageExecutionResponse {
            requireVersion(schemaVersion);
            Objects.requireNonNull(resultId, "resultId is required");
            Objects.requireNonNull(handlerKey, "handlerKey is required");
            Objects.requireNonNull(resultPort, "resultPort is required");
            Objects.requireNonNull(resource, "resource is required");
            structuredCommand = copy(structuredCommand);
            payload = copy(Objects.requireNonNull(payload, "payload is required"));
            if (!payload.isObject()
                    || (previewId == null) != (previewHash == null)) {
                throw new IllegalArgumentException("Natural CMS stage response is invalid.");
            }
            if (previewHash != null) {
                requireDigest(previewHash, "previewHash");
            }
        }

        @Override
        public JsonNode structuredCommand() {
            return copy(structuredCommand);
        }

        @Override
        public JsonNode payload() {
            return payload.deepCopy();
        }
    }

    public record JobResponse(
            String schemaVersion,
            UUID jobId,
            UUID traceId,
            UUID profileVersionId,
            int pipelineAttempt,
            int stateVersion,
            String status,
            String requestText,
            ResourceRef resource,
            JsonNode structuredCommand,
            UUID previewId,
            String previewHash,
            boolean previewValid,
            String approvalDecision,
            String approvalFeedback,
            Instant createdAt,
            Instant updatedAt) {
        public JobResponse {
            requireVersion(schemaVersion);
            structuredCommand = copy(structuredCommand);
        }

        @Override
        public JsonNode structuredCommand() {
            return copy(structuredCommand);
        }
    }

    public record HandlerResult(
            UUID resultId,
            UUID jobId,
            UUID traceId,
            int pipelineAttempt,
            String handlerKey,
            String resultPort,
            ResourceRef resource,
            JsonNode structuredCommand,
            UUID previewId,
            String previewHash,
            JsonNode payload,
            Instant recordedAt) {
        public HandlerResult {
            structuredCommand = copy(structuredCommand);
            payload = copy(payload);
        }

        @Override
        public JsonNode structuredCommand() {
            return copy(structuredCommand);
        }

        @Override
        public JsonNode payload() {
            return copy(payload);
        }
    }

    private static void requireVersion(String version) {
        if (!SCHEMA_VERSION.equals(version)) {
            throw new IllegalArgumentException("Unsupported Natural CMS schemaVersion.");
        }
    }

    private static void requireDigest(String value, String field) {
        if (value == null || SHA256.matcher(value).matches() == false) {
            throw new IllegalArgumentException(field + " is invalid.");
        }
    }

    private static JsonNode copy(JsonNode value) {
        return value == null ? null : value.deepCopy();
    }
}

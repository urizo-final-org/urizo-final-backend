package org.urizo.axmodulestudio.backend.cms.assistant;

import java.time.Instant;
import java.util.List;
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
    private static final Pattern NODE_ID = Pattern.compile("^[a-z][a-z0-9_-]{0,63}$");
    private static final Pattern SHA256 = Pattern.compile("^sha256:[0-9a-f]{64}$");
    private static final Set<String> DECISIONS = Set.of("APPROVED", "REJECTED");
    /** 화면 단위 Resource. 게시물은 별도 Resource가 아니라 {@code BOARD}에 포함한다. */
    private static final Set<String> RESOURCE_TYPES =
            Set.of("MENU", "BOARD", "CONTENT", "TEMPLATE");

    private NaturalCmsContract() { }

    public record ResourceRef(@NotBlank String type, @NotBlank String id) {
        public ResourceRef {
            if (!RESOURCE_TYPES.contains(type)
                    || id == null
                    || RESOURCE_ID.matcher(id).matches() == false) {
                throw new IllegalArgumentException("Natural CMS resource is invalid.");
            }
        }
    }

    /**
     * 파이프라인이 막은 이유. 화면만 읽고 Orchestrator는 보지 않는다.
     *
     * <p>그전까지 판정 사유는 {@code natural_cms_handler_result.payload}에만 남았고 그 표를
     * 읽는 API가 없어, 화면이 요청 문장의 낱말을 보고 어느 화면 일인지 추측해 안내했다. 그
     * 추측은 가드레일이 닫은 동작에서 반드시 틀린다 — 요청문은 이 화면에서 되는 일처럼 보이는데
     * 실제로는 관리자가 끈 것이기 때문이다.
     *
     * <p>{@link JobResponse}에 싣지 않고 따로 내는 이유는 Orchestrator가 그 응답을 허용
     * 목록으로 검사하기 때문이다. 필드를 더하면 Job 전체가 {@code WORKER_RESPONSE_INVALID}로
     * 거부돼 파이프라인이 통째로 멎는다.
     *
     * @param code 가드레일이 막았는지 지금 코드로 안 되는지. 화면이 「요청을 고치세요」와
     *             「관리자에게 문의하세요」를 가려 말하려면 분류가 필요하다
     * @param reason 모델이 쓴 한글 사유. 범위 밖 요청은 이 문장이 이미 정확하다
     */
    public record RefusalResponse(String schemaVersion, String code, String reason) {
        public RefusalResponse {
            requireVersion(schemaVersion);
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
            @NotBlank String nodeId,
            @NotBlank String handlerKey,
            @NotNull UUID resultId) {
        public StageExecutionRequest {
            requireVersion(schemaVersion);
            if (expectedStateVersion < 1
                    || executionAttempt < 1
                    || nodeId == null
                    || NODE_ID.matcher(nodeId).matches() == false
                    || handlerKey == null
                    || HANDLER_KEY.matcher(handlerKey).matches() == false) {
                throw new IllegalArgumentException("Natural CMS stage request is invalid.");
            }
        }

        public StageExecutionRequest(
                String schemaVersion,
                UUID traceId,
                UUID profileVersionId,
                int expectedStateVersion,
                int executionAttempt,
                String handlerKey,
                UUID resultId) {
            this(schemaVersion, traceId, profileVersionId, expectedStateVersion,
                    executionAttempt, NaturalCmsContract.nodeId(handlerKey),
                    handlerKey, resultId);
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

    /**
     * 화면이 Job에 대해 알 수 있는 전부.
     *
     * <p>여기에 필드를 더하지 않는다. Orchestrator가 이 응답을 허용 목록으로 검사해
     * ({@code natural_cms_domain_client.NaturalCmsJob.from_dict}) 목록에 없는 키가 하나라도
     * 있으면 {@code WORKER_RESPONSE_INVALID}로 Job 전체를 거부한다. 더하는 변경도 깨진다.
     *
     * <p>화면에만 필요한 값은 {@link RefusalResponse}처럼 별도 경로로 낸다.
     */
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
            Instant updatedAt,
            JsonNode preview) {
        public JobResponse {
            requireVersion(schemaVersion);
            structuredCommand = copy(structuredCommand);
            preview = copy(preview);
        }

        public JobResponse(String schemaVersion, UUID jobId, UUID traceId, UUID profileVersionId,
                int pipelineAttempt, int stateVersion, String status, String requestText,
                ResourceRef resource, JsonNode structuredCommand, UUID previewId, String previewHash,
                boolean previewValid, String approvalDecision, String approvalFeedback,
                Instant createdAt, Instant updatedAt) {
            this(schemaVersion, jobId, traceId, profileVersionId, pipelineAttempt, stateVersion,
                    status, requestText, resource, structuredCommand, previewId, previewHash,
                    previewValid, approvalDecision, approvalFeedback, createdAt, updatedAt, null);
        }

        @Override
        public JsonNode structuredCommand() {
            return copy(structuredCommand);
        }

        @Override
        public JsonNode preview() {
            return copy(preview);
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

    private static String nodeId(String handlerKey) {
        return handlerKey != null && handlerKey.startsWith("cms.")
                ? handlerKey.substring("cms.".length()) : null;
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

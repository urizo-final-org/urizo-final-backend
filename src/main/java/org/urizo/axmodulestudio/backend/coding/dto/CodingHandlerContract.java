package org.urizo.axmodulestudio.backend.coding.dto;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class CodingHandlerContract {

    public static final String SCHEMA_VERSION = "1.0";

    private static final Set<String> RESULT_PORTS = Set.of(
            "feasible", "infeasible", "completed", "passed",
            "changes_requested", "ready", "requested", "recorded");

    private CodingHandlerContract() { }

    public enum ResultType {
        ANALYSIS,
        CANDIDATE,
        DIFF,
        REVIEW,
        PULL_REQUEST,
        DEPLOY_REQUEST
    }

    public enum AttemptStatus {
        ACTIVE,
        REJECTED,
        COMPLETED,
        FAILED
    }

    public enum Decision {
        APPROVED,
        REJECTED
    }

    public enum ApprovalStage {
        SCOPE,
        CANDIDATE,
        GITHUB,
        CMS,
        DEPLOY
    }

    public record InitializeRequest(
            @NotBlank String schemaVersion,
            @NotNull UUID traceId,
            @NotBlank @Size(max = 10_000) String requestText) {

        public InitializeRequest {
            requireVersion(schemaVersion);
            requestText = requestText == null ? null : requestText.strip();
            if (requestText != null && requestText.isEmpty()) {
                throw new IllegalArgumentException("requestText must not be blank.");
            }
        }

        @Override
        public String toString() {
            return "InitializeRequest[schemaVersion=" + schemaVersion
                    + ", traceId=" + traceId
                    + ", requestText=REDACTED]";
        }
    }

    public record CreateCodingJobRequest(
            @NotBlank String schemaVersion,
            @NotNull UUID profileVersionId,
            @NotNull UUID projectId,
            @NotNull UUID repositoryId,
            @NotBlank @Pattern(regexp = "^[a-z][a-z0-9_-]{0,119}$") String graphStep,
            @NotBlank @Pattern(regexp = "^(sha1:[0-9a-f]{40}|sha256:[0-9a-f]{64})$")
            String baseSha,
            @NotBlank @Pattern(regexp = "^sha256:[0-9a-f]{64}$") String contextDigest,
            @NotBlank @Pattern(regexp = "^sha256:[0-9a-f]{64}$") String policyHash,
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9._-]{1,120}$") String promptVersion,
            @NotEmpty @Size(max = 3) List<@NotBlank String> allowedCapabilities,
            @NotEmpty @Size(max = 50) List<@NotBlank @Pattern(
                    regexp = "^[a-z][a-z0-9_-]{0,119}$") String> allowedNodes,
            @NotNull Instant expiresAt,
            @NotBlank @Size(max = 10_000) String requestText) {

        public CreateCodingJobRequest {
            requireVersion(schemaVersion);
            allowedCapabilities = List.copyOf(allowedCapabilities);
            allowedNodes = List.copyOf(allowedNodes);
            requestText = requestText == null ? null : requestText.strip();
            if (requestText != null && requestText.isEmpty()) {
                throw new IllegalArgumentException("requestText must not be blank.");
            }
        }

        @Override
        public String toString() {
            return "CreateCodingJobRequest[schemaVersion=" + schemaVersion
                    + ", profileVersionId=" + profileVersionId
                    + ", projectId=" + projectId + ", repositoryId=" + repositoryId
                    + ", graphStep=" + graphStep + ", baseSha=REDACTED"
                    + ", contextDigest=REDACTED, policyHash=REDACTED"
                    + ", promptVersion=" + promptVersion
                    + ", allowedCapabilities=" + allowedCapabilities
                    + ", allowedNodes=" + allowedNodes + ", expiresAt=" + expiresAt
                    + ", requestText=REDACTED]";
        }
    }

    public record CreateCodingJobResponse(
            String schemaVersion,
            CodingJobLifecycleContract.JobResponse job,
            JobRequestResponse request) { }

    public record JobRequestResponse(
            String schemaVersion,
            UUID jobId,
            UUID traceId,
            String requestText,
            String systemWorkId,
            String workSlug,
            Instant createdAt) {

        @Override
        public String toString() {
            return "JobRequestResponse[schemaVersion=" + schemaVersion
                    + ", jobId=" + jobId + ", traceId=" + traceId
                    + ", requestText=REDACTED, systemWorkId=" + systemWorkId
                    + ", workSlug=" + workSlug + ", createdAt=" + createdAt + "]";
        }
    }

    public record PutResultRequest(
            @NotBlank String schemaVersion,
            @NotNull UUID traceId,
            @Min(1) int expectedStateVersion,
            @NotBlank @Pattern(regexp = "^[a-z][a-z0-9._-]{0,119}$") String handlerKey,
            @NotNull ResultType resultType,
            @NotBlank String resultPort,
            UUID workspaceId,
            @Pattern(regexp = "^(sha1:[0-9a-f]{40}|sha256:[0-9a-f]{64})$") String candidateSha,
            @Pattern(regexp = "^sha256:[0-9a-f]{64}$") String diffDigest,
            @Pattern(regexp = "^sha256:[0-9a-f]{64}$") String validationHash,
            @NotNull JsonNode payload) {

        public PutResultRequest {
            requireVersion(schemaVersion);
            if (!RESULT_PORTS.contains(resultPort)) {
                throw new IllegalArgumentException("resultPort is not registered for Coding Handler results.");
            }
            requireRegisteredResult(handlerKey, resultType, resultPort);
            if (payload == null || !payload.isObject()) {
                throw new IllegalArgumentException("payload must be an object.");
            }
            boolean candidateRequired = resultType != ResultType.ANALYSIS;
            if (candidateRequired && candidateSha == null) {
                throw new IllegalArgumentException("candidateSha is required for this resultType.");
            }
            if (resultType == ResultType.DIFF
                    && (diffDigest == null || validationHash == null)) {
                throw new IllegalArgumentException(
                        "diffDigest and validationHash are required for a DIFF result.");
            }
            if ((resultType == ResultType.PULL_REQUEST
                    || resultType == ResultType.DEPLOY_REQUEST)
                    && validationHash == null) {
                throw new IllegalArgumentException(
                        "validationHash is required for this side-effect result.");
            }
        }

        @Override
        public String toString() {
            return "PutResultRequest[schemaVersion=" + schemaVersion
                    + ", traceId=" + traceId + ", expectedStateVersion=" + expectedStateVersion
                    + ", handlerKey=" + handlerKey + ", resultType=" + resultType
                    + ", resultPort=" + resultPort + ", workspaceId=" + workspaceId
                    + ", candidateSha=" + candidateSha + ", diffDigest=" + diffDigest
                    + ", validationHash=" + validationHash + ", payload=REDACTED]";
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record HandlerResultResponse(
            String schemaVersion,
            UUID resultId,
            UUID jobId,
            UUID traceId,
            int pipelineAttempt,
            String handlerKey,
            ResultType resultType,
            String resultPort,
            UUID workspaceId,
            String candidateSha,
            String diffDigest,
            String validationHash,
            JsonNode payload,
            Instant recordedAt) {

        @Override
        public String toString() {
            return "HandlerResultResponse[schemaVersion=" + schemaVersion
                    + ", resultId=" + resultId + ", jobId=" + jobId
                    + ", traceId=" + traceId + ", pipelineAttempt=" + pipelineAttempt
                    + ", handlerKey=" + handlerKey + ", resultType=" + resultType
                    + ", resultPort=" + resultPort + ", workspaceId=" + workspaceId
                    + ", candidateSha=" + candidateSha + ", diffDigest=" + diffDigest
                    + ", validationHash=" + validationHash + ", payload=REDACTED"
                    + ", recordedAt=" + recordedAt + "]";
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ApprovalDecisionSummary(
            UUID approvalId,
            String nodeId,
            ApprovalStage stage,
            int stageRound,
            Decision decision,
            String candidateSha,
            String validationHash,
            String feedback,
            UUID actorId,
            String actorRole,
            int resultStateVersion,
            Integer nextPipelineAttempt,
            Instant decidedAt) { }

    public record PendingApprovalSummary(
            UUID approvalId,
            String nodeId,
            ApprovalStage stage,
            int stageRound,
            String requiredRole) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AttemptAggregateResponse(
            String schemaVersion,
            UUID jobId,
            UUID traceId,
            int pipelineAttempt,
            UUID workspaceId,
            AttemptStatus status,
            String requestText,
            List<@Valid HandlerResultResponse> results,
            List<@Valid PendingApprovalSummary> pendingApprovals,
            List<@Valid ApprovalDecisionSummary> decisions,
            Instant createdAt,
            Instant finishedAt) {

        public AttemptAggregateResponse {
            results = List.copyOf(results);
            pendingApprovals = List.copyOf(pendingApprovals);
            decisions = List.copyOf(decisions);
        }

        @Override
        public String toString() {
            return "AttemptAggregateResponse[schemaVersion=" + schemaVersion
                    + ", jobId=" + jobId + ", traceId=" + traceId
                    + ", pipelineAttempt=" + pipelineAttempt + ", workspaceId=" + workspaceId
                    + ", status=" + status + ", requestText=REDACTED, results=REDACTED"
                    + ", pendingApprovals=REDACTED"
                    + ", decisions=REDACTED"
                    + ", createdAt=" + createdAt + ", finishedAt=" + finishedAt + "]";
        }
    }

    public record ApprovalDecisionRequest(
            @NotBlank String schemaVersion,
            @NotNull UUID traceId,
            @Min(1) int expectedStateVersion,
            @Min(1) @Max(3) int pipelineAttempt,
            @NotNull UUID approvalId,
            @NotBlank @Pattern(regexp = "^[a-z][a-z0-9_]{0,119}$") String nodeId,
            @NotNull ApprovalStage stage,
            @Min(1) @Max(3) int stageRound,
            @Pattern(regexp = "^(sha1:[0-9a-f]{40}|sha256:[0-9a-f]{64})$")
            String candidateSha,
            @Pattern(regexp = "^sha256:[0-9a-f]{64}$") String validationHash,
            @NotNull Decision decision,
            @Size(max = 2_000) String feedback) {

        public ApprovalDecisionRequest {
            requireVersion(schemaVersion);
            if (stage != null && !approvalNode(stage).equals(nodeId)) {
                throw new IllegalArgumentException("nodeId does not match the approval stage.");
            }
            feedback = feedback == null ? null : feedback.strip();
            if (decision == Decision.REJECTED && (feedback == null || feedback.isEmpty())) {
                throw new IllegalArgumentException("feedback is required when an approval is rejected.");
            }
            boolean candidateStage = stage == ApprovalStage.CANDIDATE
                    || stage == ApprovalStage.GITHUB
                    || stage == ApprovalStage.CMS
                    || stage == ApprovalStage.DEPLOY;
            boolean completeSubject = candidateSha != null && validationHash != null;
            boolean emptySubject = candidateSha == null && validationHash == null;
            if ((candidateStage && !completeSubject) || (!candidateStage && !emptySubject)) {
                throw new IllegalArgumentException(
                        "candidateSha and validationHash must match the approval stage.");
            }
        }

        @Override
        public String toString() {
            return "ApprovalDecisionRequest[schemaVersion=" + schemaVersion
                    + ", traceId=" + traceId + ", expectedStateVersion=" + expectedStateVersion
                    + ", pipelineAttempt=" + pipelineAttempt + ", approvalId=" + approvalId
                    + ", nodeId=" + nodeId + ", stage=" + stage
                    + ", stageRound=" + stageRound + ", candidateSha=" + candidateSha
                    + ", validationHash=" + validationHash
                    + ", decision=" + decision + ", feedback=REDACTED]";
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ApprovalDecisionResponse(
            String schemaVersion,
            UUID jobId,
            UUID traceId,
            int pipelineAttempt,
            UUID approvalId,
            String nodeId,
            ApprovalStage stage,
            int stageRound,
            String candidateSha,
            String validationHash,
            Decision decision,
            UUID actorId,
            String actorRole,
            int stateVersion,
            String status,
            Integer nextPipelineAttempt,
            Instant decidedAt) { }

    private static void requireRegisteredResult(
            String handlerKey, ResultType resultType, String resultPort) {
        boolean registered = switch (handlerKey) {
            case "coding.analyze" -> resultType == ResultType.ANALYSIS
                    && Set.of("feasible", "infeasible").contains(resultPort);
            case "coding.code" -> resultType == ResultType.CANDIDATE
                    && "completed".equals(resultPort);
            case "coding.review" -> resultType == ResultType.REVIEW
                    && Set.of("passed", "changes_requested").contains(resultPort);
            case "coding.preview" -> resultType == ResultType.DIFF
                    && "ready".equals(resultPort);
            case "coding.pr_request" -> resultType == ResultType.PULL_REQUEST
                    && "requested".equals(resultPort);
            case "coding.deploy_request" -> resultType == ResultType.DEPLOY_REQUEST
                    && "recorded".equals(resultPort);
            default -> false;
        };
        if (!registered) {
            throw new IllegalArgumentException(
                    "handlerKey, resultType and resultPort are not a registered combination.");
        }
    }

    public static String approvalNode(ApprovalStage stage) {
        return switch (stage) {
            case SCOPE -> "scope_approval";
            case CANDIDATE -> "preview_approval";
            case GITHUB -> "github_approval";
            case CMS -> "cms_approval";
            case DEPLOY -> "deploy_approval";
        };
    }

    private static void requireVersion(String value) {
        if (!SCHEMA_VERSION.equals(value)) {
            throw new IllegalArgumentException("schemaVersion must be 1.0.");
        }
    }
}

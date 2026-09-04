package org.urizo.axmodulestudio.backend.coding.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.urizo.axmodulestudio.backend.coding.dto.CodingHandlerContract;

@Service
@ConditionalOnProperty(prefix = "ax.coding.model-turn-bridge", name = "enabled", havingValue = "true")
public final class CodingHandlerResultService {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    CodingHandlerResultService(
            @Qualifier("codingModelTurnJdbcTemplate") JdbcTemplate jdbc,
            @Qualifier("codingModelTurnTransactionTemplate") TransactionTemplate transactions,
            ObjectMapper objectMapper,
            Clock clock) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public CodingHandlerContract.HandlerResultResponse put(
            String authorization,
            UUID jobId,
            int pipelineAttempt,
            UUID resultId,
            CodingHandlerContract.PutResultRequest request) {
        byte[] credentialDigest = credentialDigest(authorization);
        byte[] requestDigest = requestDigest(jobId, pipelineAttempt, resultId, request);
        try {
            CodingHandlerContract.HandlerResultResponse response = transactions.execute(status -> {
                authenticate(credentialDigest);
                jdbc.query(
                        "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                        resultSet -> { },
                        resultLockKey(resultId));
                ExistingResult existing = findExisting(resultId);
                if (existing != null) {
                    if (!existing.jobId().equals(jobId)
                            || existing.pipelineAttempt() != pipelineAttempt
                            || !MessageDigest.isEqual(existing.requestDigest(), requestDigest)) {
                        throw conflict(
                                "RESULT_ID_REUSED",
                                "The Coding Handler resultId was reused with another result.");
                    }
                    return existing.response();
                }

                JobAuthority job = requireJob(jobId);
                if (!job.traceId().equals(request.traceId())
                        || job.stateVersion() != request.expectedStateVersion()
                        || !"RUNNING".equals(job.status())) {
                    throw conflict(
                            "JOB_STATE_VERSION_CONFLICT",
                            "The Coding Handler result is not bound to the active Job state.");
                }
                AttemptRow attempt = requireCurrentAttempt(jobId, pipelineAttempt);
                bindWorkspace(jobId, attempt, request.workspaceId());
                UUID effectiveWorkspace = request.workspaceId() == null
                        ? attempt.workspaceId() : request.workspaceId();
                requireCandidateChain(jobId, pipelineAttempt, request);
                requireBoundaryAuthority(jobId, pipelineAttempt, request);

                Instant now = Instant.now(clock);
                int inserted = jdbc.update("""
                        INSERT INTO app.coding_handler_result (
                            result_id, job_id, pipeline_attempt, trace_id, handler_key,
                            result_type, result_port, workspace_id, candidate_sha,
                            diff_digest, validation_hash, payload, request_digest, recorded_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                        """,
                        resultId, jobId, pipelineAttempt, request.traceId(), request.handlerKey(),
                        request.resultType().name(), request.resultPort(), effectiveWorkspace,
                        request.candidateSha(), request.diffDigest(), request.validationHash(),
                        request.payload().toString(), requestDigest, Timestamp.from(now));
                if (inserted != 1) {
                    throw unavailable();
                }
                return response(resultId, jobId, pipelineAttempt, effectiveWorkspace, request, now);
            });
            if (response == null) {
                throw unavailable();
            }
            return response;
        }
        finally {
            Arrays.fill(credentialDigest, (byte) 0);
            Arrays.fill(requestDigest, (byte) 0);
        }
    }

    public CodingHandlerContract.HandlerResultResponse get(
            String authorization, UUID jobId, int pipelineAttempt, UUID resultId) {
        return authenticated(authorization, () -> {
            ExistingResult result = findExisting(resultId);
            if (result == null
                    || !result.jobId().equals(jobId)
                    || result.pipelineAttempt() != pipelineAttempt) {
                throw new CodingWorkerException(
                        "HANDLER_RESULT_NOT_FOUND",
                        "Coding Handler result not found.",
                        HttpStatus.NOT_FOUND);
            }
            return result.response();
        });
    }

    public CodingHandlerContract.AttemptAggregateResponse aggregate(
            String authorization, UUID jobId, int pipelineAttempt) {
        return authenticated(authorization, () -> {
            List<AggregateRow> attempts = jdbc.query("""
                    SELECT cj.trace_id, cj.status AS job_status, cj.state_version,
                           cpa.workspace_id, cpa.status, cjr.request_text,
                           cpa.created_at, cpa.finished_at
                    FROM app.coding_pipeline_attempt cpa
                    JOIN app.coding_job cj ON cj.job_id = cpa.job_id
                    LEFT JOIN app.coding_job_request cjr ON cjr.job_id = cpa.job_id
                    WHERE cpa.job_id = ? AND cpa.pipeline_attempt = ?
                    """,
                    (rs, row) -> new AggregateRow(
                            rs.getObject("trace_id", UUID.class),
                            rs.getString("job_status"),
                            rs.getInt("state_version"),
                            rs.getObject("workspace_id", UUID.class),
                            CodingHandlerContract.AttemptStatus.valueOf(rs.getString("status")),
                            rs.getString("request_text"),
                            rs.getTimestamp("created_at").toInstant(),
                            rs.getTimestamp("finished_at") == null
                                    ? null : rs.getTimestamp("finished_at").toInstant()),
                    jobId, pipelineAttempt);
            if (attempts.size() != 1) {
                throw new CodingWorkerException(
                        "PIPELINE_ATTEMPT_NOT_FOUND",
                        "Coding pipeline attempt not found.",
                        HttpStatus.NOT_FOUND);
            }
            AggregateRow attempt = attempts.get(0);
            if (attempt.requestText() == null) {
                throw conflict(
                        "CODING_JOB_REQUEST_NOT_INITIALIZED",
                        "The Coding Job request has not been initialized.");
            }
            List<CodingHandlerContract.HandlerResultResponse> results = jdbc.query("""
                    SELECT result_id, job_id, trace_id, pipeline_attempt, handler_key,
                           result_type, result_port, workspace_id, candidate_sha,
                           diff_digest, validation_hash, payload::text, recorded_at
                    FROM app.coding_handler_result
                    WHERE job_id = ? AND pipeline_attempt = ?
                    ORDER BY recorded_at, result_id
                    """, (rs, row) -> mapResult(
                            rs.getObject("result_id", UUID.class),
                            rs.getObject("job_id", UUID.class),
                            rs.getObject("trace_id", UUID.class),
                            rs.getInt("pipeline_attempt"),
                            rs.getString("handler_key"),
                            rs.getString("result_type"),
                            rs.getString("result_port"),
                            rs.getObject("workspace_id", UUID.class),
                            rs.getString("candidate_sha"),
                            rs.getString("diff_digest"),
                            rs.getString("validation_hash"),
                            rs.getString("payload"),
                            rs.getTimestamp("recorded_at").toInstant()),
                    jobId, pipelineAttempt);
            List<CodingHandlerContract.ApprovalDecisionSummary> decisions = jdbc.query("""
                    SELECT approval_id, node_id, stage, stage_round, decision, subject_candidate_sha,
                           validation_hash, feedback, actor_id, actor_role,
                           result_state_version, next_pipeline_attempt, decided_at
                    FROM app.coding_approval_decision
                    WHERE job_id = ?
                      AND (pipeline_attempt = ? OR next_pipeline_attempt = ?)
                    ORDER BY decided_at, approval_id
                    """, (rs, row) -> new CodingHandlerContract.ApprovalDecisionSummary(
                            rs.getObject("approval_id", UUID.class),
                            rs.getString("node_id"),
                            CodingHandlerContract.ApprovalStage.valueOf(rs.getString("stage")),
                            rs.getInt("stage_round"),
                            CodingHandlerContract.Decision.valueOf(rs.getString("decision")),
                            rs.getString("subject_candidate_sha"),
                            rs.getString("validation_hash"),
                            rs.getString("feedback"),
                            rs.getObject("actor_id", UUID.class),
                            rs.getString("actor_role"),
                            rs.getInt("result_state_version"),
                            (Integer) rs.getObject("next_pipeline_attempt"),
                            rs.getTimestamp("decided_at").toInstant()),
                    jobId, pipelineAttempt, pipelineAttempt);
            List<CodingHandlerContract.PendingApprovalSummary> pendingApprovals =
                    pendingApprovals(attempt, jobId, pipelineAttempt);
            return new CodingHandlerContract.AttemptAggregateResponse(
                    CodingHandlerContract.SCHEMA_VERSION,
                    jobId,
                    attempt.traceId(),
                    pipelineAttempt,
                    attempt.workspaceId(),
                    attempt.status(),
                    attempt.requestText(),
                    results,
                    pendingApprovals,
                    decisions,
                    attempt.createdAt(),
                    attempt.finishedAt());
        });
    }

    JobRequestIdentity jobRequestIdentity(UUID jobId) {
        List<JobRequestIdentity> rows = jdbc.query("""
                SELECT system_work_id, work_slug
                FROM app.coding_job_request
                WHERE job_id = ?
                """, (rs, row) -> new JobRequestIdentity(
                        rs.getString("system_work_id"), rs.getString("work_slug")), jobId);
        if (rows.size() != 1
                || rows.get(0).systemWorkId() == null
                || rows.get(0).workSlug() == null
                || !rows.get(0).workSlug().startsWith("system-")) {
            throw conflict(
                    "CODING_JOB_REQUEST_NOT_INITIALIZED",
                    "The Coding Job request identity has not been initialized.");
        }
        return rows.get(0);
    }

    private List<CodingHandlerContract.PendingApprovalSummary> pendingApprovals(
            AggregateRow attempt, UUID jobId, int pipelineAttempt) {
        if (!"WAITING_APPROVAL".equals(attempt.jobStatus())
                || attempt.status() != CodingHandlerContract.AttemptStatus.ACTIVE) {
            return List.of();
        }
        return CodingApprovalReadiness.find(
                        jdbc,
                        objectMapper,
                        jobId,
                        attempt.traceId(),
                        attempt.stateVersion(),
                        pipelineAttempt)
                .map(ready -> List.of(new CodingHandlerContract.PendingApprovalSummary(
                        ready.approvalId(),
                        ready.nodeId(),
                        ready.stage(),
                        ready.stageRound(),
                        ready.requiredRole())))
                .orElseGet(List::of);
    }

    private AttemptRow requireCurrentAttempt(UUID jobId, int requestedAttempt) {
        jdbc.update("""
                INSERT INTO app.coding_pipeline_attempt (job_id, pipeline_attempt, status)
                VALUES (?, 1, 'ACTIVE')
                ON CONFLICT (job_id, pipeline_attempt) DO NOTHING
                """, jobId);
        List<AttemptRow> attempts = jdbc.query("""
                SELECT pipeline_attempt, workspace_id, status
                FROM app.coding_pipeline_attempt
                WHERE job_id = ?
                ORDER BY pipeline_attempt DESC
                LIMIT 1
                FOR UPDATE
                """, (rs, row) -> new AttemptRow(
                        rs.getInt("pipeline_attempt"),
                        rs.getObject("workspace_id", UUID.class),
                        rs.getString("status")), jobId);
        if (attempts.size() != 1
                || attempts.get(0).pipelineAttempt() != requestedAttempt
                || !"ACTIVE".equals(attempts.get(0).status())) {
            throw conflict(
                    "JOB_STATE_VERSION_CONFLICT",
                    "The requested Coding pipeline attempt is not active.");
        }
        return attempts.get(0);
    }

    private void bindWorkspace(UUID jobId, AttemptRow attempt, UUID requestedWorkspace) {
        if (requestedWorkspace == null) {
            return;
        }
        if (attempt.workspaceId() != null && !attempt.workspaceId().equals(requestedWorkspace)) {
            throw conflict(
                    "WORKSPACE_BINDING_CONFLICT",
                    "The Coding pipeline attempt is already bound to another workspace.");
        }
        if (attempt.workspaceId() == null) {
            int updated = jdbc.update("""
                    UPDATE app.coding_pipeline_attempt
                    SET workspace_id = ?, updated_at = ?
                    WHERE job_id = ? AND pipeline_attempt = ?
                      AND status = 'ACTIVE' AND workspace_id IS NULL
                    """, requestedWorkspace, Timestamp.from(Instant.now(clock)),
                    jobId, attempt.pipelineAttempt());
            if (updated != 1) {
                throw conflict(
                        "WORKSPACE_BINDING_CONFLICT",
                        "The Coding pipeline workspace binding changed concurrently.");
            }
        }
    }

    private void requireBoundaryAuthority(
            UUID jobId,
            int pipelineAttempt,
            CodingHandlerContract.PutResultRequest request) {
        if (request.resultType() == CodingHandlerContract.ResultType.PULL_REQUEST
                && "coding.pr_complete".equals(request.handlerKey())) {
            ResultSubject requested = latestSubject(
                    jobId, pipelineAttempt, "coding.pr_request", "requested");
            ApprovalSubject githubApproval = latestApproval(
                    jobId, pipelineAttempt, CodingHandlerContract.ApprovalStage.GITHUB);
            requireApprovedSubject(requested, githubApproval);
            if (!matches(request, requested)) {
                throw subjectConflict();
            }
            return;
        }
        if (request.resultType() == CodingHandlerContract.ResultType.DEV_MERGE) {
            ResultSubject pullRequest = latestSubject(
                    jobId, pipelineAttempt, "coding.pr_complete", "completed");
            JsonNode pullRequestPayload = latestPayload(
                    jobId, pipelineAttempt, "coding.pr_complete", "completed");
            ResultSubject deployRequest = latestSubject(
                    jobId, pipelineAttempt, "coding.deploy_request", "recorded");
            JsonNode deployRequestPayload = latestPayload(
                    jobId, pipelineAttempt, "coding.deploy_request", "recorded");
            ApprovalSubject deployApproval = latestApproval(
                    jobId, pipelineAttempt, CodingHandlerContract.ApprovalStage.DEPLOY);
            requireApprovedSubject(deployRequest, deployApproval);
            if (!matches(request, pullRequest)
                    || pullRequestPayload == null
                    || deployRequest == null
                    || deployRequestPayload == null
                    || !deployRequestPayload.hasNonNull("deploymentRequestId")
                    || !Objects.equals(pullRequest.candidateSha(), deployRequest.candidateSha())
                    || !Objects.equals(pullRequestPayload.path("repository").asText(),
                            deployRequestPayload.path("repository").asText())
                    || pullRequestPayload.path("prNumber").asInt(-1)
                            != deployRequestPayload.path("prNumber").asInt(-2)) {
                throw subjectConflict();
            }
            return;
        }
        if (request.resultType() == CodingHandlerContract.ResultType.DEPLOYMENT) {
            ResultSubject deployRequest = latestSubject(
                    jobId, pipelineAttempt, "coding.deploy_request", "recorded");
            JsonNode deployRequestPayload = latestPayload(
                    jobId, pipelineAttempt, "coding.deploy_request", "recorded");
            ResultSubject merge = latestSubject(
                    jobId, pipelineAttempt, "coding.dev_merge_check", "merged");
            ApprovalSubject approval = latestApproval(
                    jobId, pipelineAttempt, CodingHandlerContract.ApprovalStage.DEPLOY);
            requireApprovedSubject(deployRequest, approval);
            String deploymentRequestId = request.payload()
                    .path("deploymentRequestId").asText();
            String mergeSha = request.payload().path("mergeSha").asText();
            String expectedExecutionId = UUID.nameUUIDFromBytes(
                    ("deployment-execution:" + deploymentRequestId + ":" + mergeSha)
                            .getBytes(StandardCharsets.UTF_8)).toString();
            if (!matches(request, deployRequest)
                    || merge == null
                    || !Objects.equals(request.candidateSha(), merge.candidateSha())
                    || !expectedExecutionId.equals(
                            request.payload().path("deploymentExecutionId").asText())
                    || deployRequestPayload == null
                    || !Objects.equals(deploymentRequestId,
                            deployRequestPayload.path("deploymentRequestId").asText())
                    || !Objects.equals(request.payload().path("adapterKey").asText(),
                            deployRequestPayload.path("adapterKey").asText())
                    || !Objects.equals(request.payload().path("targetKey").asText(),
                            deployRequestPayload.path("targetKey").asText())
                    || !Objects.equals(request.payload().path("configDigest").asText(),
                            deployRequestPayload.path("configDigest").asText())) {
                throw subjectConflict();
            }
            return;
        }
        boolean versionFourDeployRequest =
                request.resultType() == CodingHandlerContract.ResultType.DEPLOY_REQUEST
                && request.payload().hasNonNull("deploymentRequestId");
        if (versionFourDeployRequest) {
            ResultSubject pullRequest = latestSubject(
                    jobId, pipelineAttempt, "coding.pr_complete", "completed");
            ApprovalSubject githubApproval = latestApproval(
                    jobId, pipelineAttempt, CodingHandlerContract.ApprovalStage.GITHUB);
            requireApprovedSubject(pullRequest, githubApproval);
            JsonNode payload = request.payload();
            ObjectNode subject = objectMapper.createObjectNode();
            for (String field : List.of(
                    "jobId", "pipelineAttempt", "repository", "prNumber", "candidateSha",
                    "sourceValidationHash", "adapterKey", "targetKey", "configDigest")) {
                JsonNode value = payload.get(field);
                if (value == null) {
                    throw subjectConflict();
                }
                subject.set(field, value.deepCopy());
            }
            boolean valid = pullRequest != null
                    && jobId.toString().equals(payload.path("jobId").asText())
                    && pipelineAttempt == payload.path("pipelineAttempt").asInt(-1)
                    && Objects.equals(request.candidateSha(), pullRequest.candidateSha())
                    && Objects.equals(request.candidateSha(), payload.path("candidateSha").asText())
                    && Objects.equals(pullRequest.validationHash(),
                            payload.path("sourceValidationHash").asText())
                    && Objects.equals(request.validationHash(), jsonDigest(subject))
                    && UUID.nameUUIDFromBytes(
                            ("deployment-request:" + request.validationHash())
                                    .getBytes(StandardCharsets.UTF_8)).toString().equals(
                                            payload.path("deploymentRequestId").asText())
                    && !payload.path("adapterKey").asText().isBlank()
                    && !payload.path("targetKey").asText().isBlank()
                    && payload.path("configDigest").asText()
                            .matches("^sha256:[0-9a-f]{64}$");
            if (!valid) {
                throw subjectConflict();
            }
            return;
        }
        CodingHandlerContract.ApprovalStage requiredStage = switch (request.resultType()) {
            case PULL_REQUEST -> CodingHandlerContract.ApprovalStage.CANDIDATE;
            case DEPLOY_REQUEST -> CodingHandlerContract.ApprovalStage.DEPLOY;
            default -> null;
        };
        if (requiredStage == null) {
            return;
        }
        ResultSubject preview = latestSubject(
                jobId, pipelineAttempt, "coding.preview", "ready");
        ApprovalSubject approval = latestApproval(jobId, pipelineAttempt, requiredStage);
        requireBoundarySubject(
                request.resultType(),
                new ResultSubject(request.candidateSha(), request.validationHash()),
                preview,
                approval);
    }

    private ResultSubject latestSubject(
            UUID jobId, int pipelineAttempt, String handlerKey, String port) {
        List<ResultSubject> rows = jdbc.query("""
                SELECT candidate_sha, validation_hash
                FROM app.coding_handler_result
                WHERE job_id = ? AND pipeline_attempt = ?
                  AND handler_key = ? AND result_port = ?
                ORDER BY recorded_at DESC, result_id DESC
                LIMIT 1
                """, (rs, row) -> new ResultSubject(
                        rs.getString("candidate_sha"), rs.getString("validation_hash")),
                jobId, pipelineAttempt, handlerKey, port);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private JsonNode latestPayload(
            UUID jobId, int pipelineAttempt, String handlerKey, String port) {
        List<String> rows = jdbc.query("""
                SELECT payload::text
                FROM app.coding_handler_result
                WHERE job_id = ? AND pipeline_attempt = ?
                  AND handler_key = ? AND result_port = ?
                ORDER BY recorded_at DESC, result_id DESC
                LIMIT 1
                """, (rs, row) -> rs.getString("payload"),
                jobId, pipelineAttempt, handlerKey, port);
        if (rows.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.readTree(rows.get(0));
        }
        catch (JsonProcessingException failure) {
            throw unavailable();
        }
    }

    private ApprovalSubject latestApproval(
            UUID jobId,
            int pipelineAttempt,
            CodingHandlerContract.ApprovalStage stage) {
        List<ApprovalSubject> rows = jdbc.query("""
                SELECT decision, subject_candidate_sha, validation_hash
                FROM app.coding_approval_decision
                WHERE job_id = ? AND pipeline_attempt = ? AND stage = ?
                ORDER BY decided_at DESC, approval_id DESC
                LIMIT 1
                """, (rs, row) -> new ApprovalSubject(
                        CodingHandlerContract.Decision.valueOf(rs.getString("decision")),
                        rs.getString("subject_candidate_sha"), rs.getString("validation_hash")),
                jobId, pipelineAttempt, stage.name());
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static boolean matches(
            CodingHandlerContract.PutResultRequest request, ResultSubject subject) {
        return subject != null
                && Objects.equals(request.candidateSha(), subject.candidateSha())
                && Objects.equals(request.validationHash(), subject.validationHash());
    }

    private static boolean approved(ResultSubject subject, ApprovalSubject approval) {
        return subject != null
                && approval != null
                && approval.decision() == CodingHandlerContract.Decision.APPROVED
                && Objects.equals(subject.candidateSha(), approval.candidateSha())
                && Objects.equals(subject.validationHash(), approval.validationHash());
    }

    static void requireApprovedSubject(ResultSubject subject, ApprovalSubject approval) {
        if (!approved(subject, approval)) {
            throw subjectConflict();
        }
    }

    private void requireCandidateChain(
            UUID jobId,
            int pipelineAttempt,
            CodingHandlerContract.PutResultRequest request) {
        if (request.resultType() != CodingHandlerContract.ResultType.REVIEW
                && request.resultType() != CodingHandlerContract.ResultType.DIFF) {
            return;
        }
        List<ResultSubject> codeResults = jdbc.query("""
                SELECT candidate_sha, validation_hash
                FROM app.coding_handler_result
                WHERE job_id = ? AND pipeline_attempt = ?
                  AND handler_key = 'coding.code'
                  AND result_type = 'CANDIDATE'
                  AND result_port = 'completed'
                ORDER BY recorded_at DESC, result_id DESC
                LIMIT 1
                """, (rs, row) -> new ResultSubject(
                        rs.getString("candidate_sha"), rs.getString("validation_hash")),
                jobId, pipelineAttempt);
        List<ReviewSubject> reviews = request.resultType() == CodingHandlerContract.ResultType.DIFF
                ? jdbc.query("""
                        SELECT result_port, candidate_sha
                        FROM app.coding_handler_result
                        WHERE job_id = ? AND pipeline_attempt = ?
                          AND handler_key = 'coding.review'
                          AND result_type = 'REVIEW'
                        ORDER BY recorded_at DESC, result_id DESC
                        LIMIT 1
                        """, (rs, row) -> new ReviewSubject(
                                rs.getString("result_port"), rs.getString("candidate_sha")),
                        jobId, pipelineAttempt)
                : List.of();
        requireCandidateChain(
                request.resultType(),
                request.candidateSha(),
                codeResults.isEmpty() ? null : codeResults.get(0).candidateSha(),
                reviews.isEmpty() ? null : reviews.get(0));
    }

    static void requireCandidateChain(
            CodingHandlerContract.ResultType resultType,
            String requestedCandidate,
            String latestCodeCandidate,
            ReviewSubject latestReview) {
        if (resultType == CodingHandlerContract.ResultType.REVIEW) {
            if (latestCodeCandidate == null
                    || !Objects.equals(requestedCandidate, latestCodeCandidate)) {
                throw subjectConflict();
            }
            return;
        }
        if (resultType == CodingHandlerContract.ResultType.DIFF
                && (latestCodeCandidate == null
                    || latestReview == null
                    || !"passed".equals(latestReview.resultPort())
                    || !Objects.equals(requestedCandidate, latestCodeCandidate)
                    || !Objects.equals(requestedCandidate, latestReview.candidateSha()))) {
            throw subjectConflict();
        }
    }

    static void requireBoundarySubject(
            CodingHandlerContract.ResultType resultType,
            ResultSubject requested,
            ResultSubject latestPreview,
            ApprovalSubject approval) {
        if (resultType != CodingHandlerContract.ResultType.PULL_REQUEST
                && resultType != CodingHandlerContract.ResultType.DEPLOY_REQUEST) {
            return;
        }
        boolean previewMatches = latestPreview != null
                && Objects.equals(requested.candidateSha(), latestPreview.candidateSha())
                && Objects.equals(requested.validationHash(), latestPreview.validationHash());
        boolean approvalMatches = approval != null
                && approval.decision() == CodingHandlerContract.Decision.APPROVED
                && Objects.equals(requested.candidateSha(), approval.candidateSha())
                && Objects.equals(requested.validationHash(), approval.validationHash());
        if (!previewMatches || !approvalMatches) {
            throw subjectConflict();
        }
    }

    static String resultLockKey(UUID resultId) {
        return "CODING_HANDLER_RESULT:" + resultId;
    }

    private static CodingWorkerException subjectConflict() {
        return conflict(
                "RESULT_SUBJECT_CONFLICT",
                "The Coding Handler result is not bound to the latest authorized candidate.");
    }

    private ExistingResult findExisting(UUID resultId) {
        List<ExistingResult> rows = jdbc.query("""
                SELECT result_id, job_id, trace_id, pipeline_attempt, handler_key,
                       result_type, result_port, workspace_id, candidate_sha,
                       diff_digest, validation_hash, payload::text, request_digest, recorded_at
                FROM app.coding_handler_result
                WHERE result_id = ?
                """, (rs, row) -> new ExistingResult(
                        rs.getObject("job_id", UUID.class),
                        rs.getInt("pipeline_attempt"),
                        rs.getBytes("request_digest"),
                        mapResult(
                                rs.getObject("result_id", UUID.class),
                                rs.getObject("job_id", UUID.class),
                                rs.getObject("trace_id", UUID.class),
                                rs.getInt("pipeline_attempt"),
                                rs.getString("handler_key"),
                                rs.getString("result_type"),
                                rs.getString("result_port"),
                                rs.getObject("workspace_id", UUID.class),
                                rs.getString("candidate_sha"),
                                rs.getString("diff_digest"),
                                rs.getString("validation_hash"),
                                rs.getString("payload"),
                                rs.getTimestamp("recorded_at").toInstant())), resultId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private CodingHandlerContract.HandlerResultResponse mapResult(
            UUID resultId,
            UUID jobId,
            UUID traceId,
            int pipelineAttempt,
            String handlerKey,
            String resultType,
            String resultPort,
            UUID workspaceId,
            String candidateSha,
            String diffDigest,
            String validationHash,
            String payload,
            Instant recordedAt) {
        try {
            return new CodingHandlerContract.HandlerResultResponse(
                    CodingHandlerContract.SCHEMA_VERSION,
                    resultId,
                    jobId,
                    traceId,
                    pipelineAttempt,
                    handlerKey,
                    CodingHandlerContract.ResultType.valueOf(resultType),
                    resultPort,
                    workspaceId,
                    candidateSha,
                    diffDigest,
                    validationHash,
                    objectMapper.readTree(payload),
                    recordedAt);
        }
        catch (JsonProcessingException | IllegalArgumentException failure) {
            throw unavailable();
        }
    }

    private static CodingHandlerContract.HandlerResultResponse response(
            UUID resultId,
            UUID jobId,
            int pipelineAttempt,
            UUID workspaceId,
            CodingHandlerContract.PutResultRequest request,
            Instant recordedAt) {
        return new CodingHandlerContract.HandlerResultResponse(
                CodingHandlerContract.SCHEMA_VERSION,
                resultId,
                jobId,
                request.traceId(),
                pipelineAttempt,
                request.handlerKey(),
                request.resultType(),
                request.resultPort(),
                workspaceId,
                request.candidateSha(),
                request.diffDigest(),
                request.validationHash(),
                request.payload(),
                recordedAt);
    }

    /**
     * The repository this Job works in.
     *
     * <p>The stage that queues the build has to name a repository, and a Job's own row is the
     * only place that answer is recorded. Read here rather than carried through the aggregate
     * response: the worker contract is shared with the Python runtime, and a field only the
     * build stage reads does not belong in it.
     */
    public String jobRepository(UUID jobId) {
        List<UUID> identifiers = jdbc.query(
                "SELECT repository_id FROM app.coding_job WHERE job_id = ?",
                (rs, row) -> rs.getObject("repository_id", UUID.class), jobId);
        if (identifiers.size() != 1) {
            throw new CodingWorkerException(
                    "JOB_NOT_FOUND", "Authoritative Coding Job not found.", HttpStatus.NOT_FOUND);
        }
        return CodingRepositories.nameOf(identifiers.get(0));
    }

    private JobAuthority requireJob(UUID jobId) {
        List<JobAuthority> jobs = jdbc.query("""
                SELECT trace_id, status, state_version
                FROM app.coding_job
                WHERE job_id = ? AND authority_source = 'SPRING_CONTROL_PLANE'
                FOR SHARE
                """, (rs, row) -> new JobAuthority(
                        rs.getObject("trace_id", UUID.class),
                        rs.getString("status"),
                        rs.getInt("state_version")), jobId);
        if (jobs.size() != 1) {
            throw new CodingWorkerException(
                    "JOB_NOT_FOUND", "Authoritative Coding Job not found.", HttpStatus.NOT_FOUND);
        }
        return jobs.get(0);
    }

    private <T> T authenticated(String authorization, java.util.function.Supplier<T> action) {
        byte[] digest = credentialDigest(authorization);
        try {
            T result = transactions.execute(status -> {
                authenticate(digest);
                return action.get();
            });
            if (result == null) {
                throw unavailable();
            }
            return result;
        }
        finally {
            Arrays.fill(digest, (byte) 0);
        }
    }

    private void authenticate(byte[] digest) {
        Instant now = Instant.now(clock);
        List<UUID> matches = jdbc.query("""
                SELECT credential_id
                FROM app.coding_service_credential
                WHERE credential_digest = ?
                  AND status IN ('ACTIVE', 'RETIRING')
                  AND valid_from <= ?
                  AND (valid_until IS NULL OR valid_until > ?)
                FOR UPDATE
                """, (rs, row) -> rs.getObject("credential_id", UUID.class),
                digest, Timestamp.from(now), Timestamp.from(now));
        if (matches.size() != 1) {
            throw new CodingWorkerException(
                    "SERVICE_AUTHENTICATION_FAILED",
                    "Service authentication failed.",
                    HttpStatus.UNAUTHORIZED);
        }
        jdbc.update("UPDATE app.coding_service_credential SET last_used_at = ? WHERE credential_id = ?",
                Timestamp.from(now), matches.get(0));
    }

    byte[] requestDigest(
            UUID jobId,
            int pipelineAttempt,
            UUID resultId,
            CodingHandlerContract.PutResultRequest request) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(jobId.toString().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(Integer.toString(pipelineAttempt).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(resultId.toString().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            ObjectNode semanticRequest = objectMapper.valueToTree(request);
            semanticRequest.remove("expectedStateVersion");
            digest.update(objectMapper.writeValueAsBytes(
                    canonical(semanticRequest)));
            return digest.digest();
        }
        catch (NoSuchAlgorithmException | JsonProcessingException failure) {
            throw unavailable();
        }
    }

    private String jsonDigest(JsonNode value) {
        try {
            return "sha256:" + java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(objectMapper.writeValueAsBytes(canonical(value))));
        }
        catch (NoSuchAlgorithmException | JsonProcessingException failure) {
            throw unavailable();
        }
    }

    private JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            ObjectNode sorted = objectMapper.createObjectNode();
            java.util.TreeSet<String> fields = new java.util.TreeSet<>();
            value.fieldNames().forEachRemaining(fields::add);
            fields.forEach(field -> sorted.set(field, canonical(value.get(field))));
            return sorted;
        }
        if (value.isArray()) {
            ArrayNode ordered = objectMapper.createArrayNode();
            value.forEach(item -> ordered.add(canonical(item)));
            return ordered;
        }
        return value.deepCopy();
    }

    private static byte[] credentialDigest(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")
                || authorization.length() <= 7) {
            throw new CodingWorkerException(
                    "SERVICE_AUTHENTICATION_FAILED",
                    "Service authentication failed.",
                    HttpStatus.UNAUTHORIZED);
        }
        byte[] token = authorization.substring(7).getBytes(StandardCharsets.UTF_8);
        try {
            return MessageDigest.getInstance("SHA-256").digest(token);
        }
        catch (NoSuchAlgorithmException failure) {
            throw unavailable();
        }
        finally {
            Arrays.fill(token, (byte) 0);
        }
    }

    private static CodingWorkerException conflict(String code, String message) {
        return new CodingWorkerException(code, message, HttpStatus.CONFLICT);
    }

    private static CodingWorkerException unavailable() {
        return new CodingWorkerException(
                "INTERNAL_TRANSIENT_ERROR",
                "The Coding Handler result store is unavailable.",
                HttpStatus.SERVICE_UNAVAILABLE,
                true,
                1_000L);
    }

    private record JobAuthority(UUID traceId, String status, int stateVersion) { }
    private record AttemptRow(int pipelineAttempt, UUID workspaceId, String status) { }
    private record ExistingResult(
            UUID jobId,
            int pipelineAttempt,
            byte[] requestDigest,
            CodingHandlerContract.HandlerResultResponse response) { }
    private record AggregateRow(
            UUID traceId,
            String jobStatus,
            int stateVersion,
            UUID workspaceId,
            CodingHandlerContract.AttemptStatus status,
            String requestText,
            Instant createdAt,
            Instant finishedAt) { }
    record ResultSubject(String candidateSha, String validationHash) { }
    record ApprovalSubject(
            CodingHandlerContract.Decision decision,
            String candidateSha,
            String validationHash) { }
    record ReviewSubject(String resultPort, String candidateSha) { }
    record JobRequestIdentity(String systemWorkId, String workSlug) { }
}

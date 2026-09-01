package org.urizo.axmodulestudio.backend.coding.service;

import java.io.IOException;

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
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.urizo.axmodulestudio.backend.auth.entity.AdminRole;
import org.urizo.axmodulestudio.backend.auth.security.AuthenticatedActor;
import org.urizo.axmodulestudio.backend.coding.dto.CodingHandlerContract;
import org.urizo.axmodulestudio.backend.coding.dto.CodingJobLifecycleContract;
import org.urizo.axmodulestudio.backend.coding.repository.CodingJobLifecycleRepository;

@Service
@Profile("dev & coding-job-local-fixture")
@ConditionalOnProperty(prefix = "ax.coding.job-lifecycle", name = "enabled", havingValue = "true")
public final class CodingHandlerCommandService {

    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile(
            "^[A-Za-z0-9][A-Za-z0-9._:-]{7,127}$");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final CodingJobLifecycleRepository lifecycle;
    private final CodingJobLifecycleService lifecycleService;
    private final CodingJobLifecycleRequestDigester lifecycleDigester;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    CodingHandlerCommandService(
            @Qualifier("codingJobLifecycleJdbcTemplate") JdbcTemplate jdbc,
            @Qualifier("codingJobLifecycleTransactionTemplate") TransactionTemplate transactions,
            CodingJobLifecycleRepository lifecycle,
            CodingJobLifecycleService lifecycleService,
            CodingJobLifecycleRequestDigester lifecycleDigester,
            ObjectMapper objectMapper,
            Clock clock) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.lifecycle = lifecycle;
        this.lifecycleService = lifecycleService;
        this.lifecycleDigester = lifecycleDigester;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public CodingHandlerContract.CreateCodingJobResponse create(
            AuthenticatedActor actor,
            UUID traceId,
            String idempotencyKey,
            CodingHandlerContract.CreateCodingJobRequest request) {
        Objects.requireNonNull(actor, "actor is required");
        Objects.requireNonNull(traceId, "traceId is required");
        requireIdempotencyKey(idempotencyKey);
        CodingHandlerContract.CreateCodingJobResponse response = transactions.execute(status -> {
            CodingJobLifecycleContract.CreateRequest authoritative =
                    authoritativeCreateRequest(actor, request);
            CodingJobLifecycleContract.JobResponse job = lifecycleService.create(
                    traceId, idempotencyKey, authoritative);
            CodingHandlerContract.JobRequestResponse initialized = initialize(
                    actor,
                    job.jobId(),
                    new CodingHandlerContract.InitializeRequest(
                            CodingHandlerContract.SCHEMA_VERSION,
                            job.traceId(),
                            request.requestText()));
            return new CodingHandlerContract.CreateCodingJobResponse(
                    CodingHandlerContract.SCHEMA_VERSION, job, initialized);
        });
        if (response == null) {
            throw unavailable();
        }
        return response;
    }

    public CodingHandlerContract.JobRequestResponse initialize(
            AuthenticatedActor actor,
            UUID jobId,
            CodingHandlerContract.InitializeRequest request) {
        Objects.requireNonNull(actor, "actor is required");
        Objects.requireNonNull(jobId, "jobId is required");
        byte[] digest = digest("INITIALIZE", jobId, actor.actorId(), request);
        try {
            CodingHandlerContract.JobRequestResponse response = transactions.execute(status -> {
                JobAuthority job = requireJob(jobId, false);
                if (!job.actorId().equals(actor.actorId())) {
                    throw failure(
                            "CODING_JOB_ACTOR_MISMATCH",
                            "Only the Coding Job actor may initialize its request.",
                            HttpStatus.FORBIDDEN);
                }
                if (!job.traceId().equals(request.traceId())) {
                    throw conflict(
                            "JOB_TRACE_CONFLICT",
                            "traceId does not match the authoritative Coding Job.");
                }
                ExistingRequest existing = findRequest(jobId);
                if (existing != null) {
                    if (!MessageDigest.isEqual(existing.requestDigest(), digest)) {
                        throw conflict(
                                "CODING_JOB_REQUEST_ALREADY_INITIALIZED",
                                "The Coding Job request is immutable after initialization.");
                    }
                    ensureAttemptOne(jobId);
                    return existing.response();
                }
                if (job.terminal()) {
                    throw conflict(
                            "JOB_TERMINAL",
                            "A terminal Coding Job cannot be initialized.");
                }
                Instant now = Instant.now(clock);
                CodingJobIdentity.WorkIdentity workIdentity =
                        CodingJobIdentity.workIdentity(jobId);
                int inserted = jdbc.update("""
                        INSERT INTO app.coding_job_request (
                            job_id, request_text, system_work_id, work_slug,
                            request_digest, created_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                        jobId,
                        request.requestText(),
                        workIdentity.systemWorkId(),
                        workIdentity.workSlug(),
                        digest,
                        Timestamp.from(now));
                if (inserted != 1) {
                    throw unavailable();
                }
                ensureAttemptOne(jobId);
                return new CodingHandlerContract.JobRequestResponse(
                        CodingHandlerContract.SCHEMA_VERSION,
                        jobId,
                        job.traceId(),
                        request.requestText(),
                        workIdentity.systemWorkId(),
                        workIdentity.workSlug(),
                        now);
            });
            if (response == null) {
                throw unavailable();
            }
            return response;
        }
        finally {
            Arrays.fill(digest, (byte) 0);
        }
    }

    public CodingHandlerContract.ApprovalDecisionResponse decide(
            AuthenticatedActor actor,
            UUID jobId,
            String idempotencyKey,
            CodingHandlerContract.ApprovalDecisionRequest request) {
        Objects.requireNonNull(actor, "actor is required");
        Objects.requireNonNull(jobId, "jobId is required");
        requireIdempotencyKey(idempotencyKey);
        UUID expectedApprovalId = CodingApprovalId.forStage(
                jobId,
                request.pipelineAttempt(),
                request.nodeId(),
                request.stage(),
                request.stageRound());
        if (!expectedApprovalId.equals(request.approvalId())) {
            throw conflict(
                    "APPROVAL_ID_MISMATCH",
                    "approvalId does not match the Coding approval identity.");
        }
        byte[] requestDigest = digest("APPROVAL", jobId, actor.actorId(), request);
        try {
            CodingHandlerContract.ApprovalDecisionResponse response =
                    transactions.execute(status -> decideCurrent(
                            actor, jobId, idempotencyKey, requestDigest, request));
            if (response == null) {
                throw unavailable();
            }
            return response;
        }
        finally {
            Arrays.fill(requestDigest, (byte) 0);
        }
    }

    private CodingHandlerContract.ApprovalDecisionResponse decideCurrent(
            AuthenticatedActor actor,
            UUID jobId,
            String idempotencyKey,
            byte[] requestDigest,
            CodingHandlerContract.ApprovalDecisionRequest request) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                resultSet -> { }, "CODING_APPROVAL:" + jobId + ":" + idempotencyKey);
        ExistingDecision replay = findDecisionReplay(jobId, idempotencyKey);
        if (replay != null) {
            if (!MessageDigest.isEqual(replay.requestDigest(), requestDigest)) {
                throw conflict(
                        "IDEMPOTENCY_KEY_REUSED",
                        "Idempotency-Key was reused with another Coding approval decision.");
            }
            return decodeDecision(replay.responseJson());
        }
        if (approvalAlreadyExists(jobId, request.approvalId())) {
            throw conflict(
                    "APPROVAL_ALREADY_DECIDED",
                    "The Coding approval has already been decided.");
        }

        JobAuthority job = requireJob(jobId, true);
        if (!job.traceId().equals(request.traceId())
                || job.stateVersion() != request.expectedStateVersion()
                || !"WAITING_APPROVAL".equals(job.status())) {
            throw conflict(
                    "JOB_STATE_VERSION_CONFLICT",
                    "The Coding approval is not bound to the active Job state.");
        }
        AttemptAuthority attempt = requireActiveAttempt(jobId, request.pipelineAttempt());
        CodingApprovalReadiness.ReadyApproval ready = CodingApprovalReadiness.find(
                        jdbc,
                        objectMapper,
                        jobId,
                        job.traceId(),
                        job.stateVersion(),
                        attempt.pipelineAttempt())
                .orElseThrow(() -> conflict(
                        "APPROVAL_STAGE_NOT_READY",
                        "No Coding approval stage is ready for this Job state."));
        requireRole(actor.role(), ready.requiredRole());
        if (!ready.approvalId().equals(request.approvalId())
                || !ready.nodeId().equals(request.nodeId())
                || ready.stage() != request.stage()
                || ready.stageRound() != request.stageRound()
                || !Objects.equals(ready.candidateSha(), request.candidateSha())
                || !Objects.equals(ready.validationHash(), request.validationHash())) {
            throw conflict(
                    "APPROVAL_STAGE_NOT_READY",
                    "The requested Coding approval is out of order or bound to stale evidence.");
        }
        Instant now = Instant.now(clock);
        Integer nextAttempt = null;
        CodingJobLifecycleContract.Status target = CodingJobLifecycleContract.Status.RUNNING;
        if (request.decision() == CodingHandlerContract.Decision.REJECTED) {
            int updated = jdbc.update("""
                    UPDATE app.coding_pipeline_attempt
                    SET status = 'REJECTED', finished_at = ?, updated_at = ?
                    WHERE job_id = ? AND pipeline_attempt = ? AND status = 'ACTIVE'
                    """, Timestamp.from(now), Timestamp.from(now), jobId, attempt.pipelineAttempt());
            if (updated != 1) {
                throw conflict(
                        "PIPELINE_ATTEMPT_CONFLICT",
                        "The Coding pipeline attempt changed concurrently.");
            }
            if (request.stage() == CodingHandlerContract.ApprovalStage.CANDIDATE
                    && attempt.pipelineAttempt() < 3) {
                nextAttempt = attempt.pipelineAttempt() + 1;
                jdbc.update("""
                        INSERT INTO app.coding_pipeline_attempt (
                            job_id, pipeline_attempt, status, created_at, updated_at)
                        VALUES (?, ?, 'ACTIVE', ?, ?)
                        """, jobId, nextAttempt, Timestamp.from(now), Timestamp.from(now));
            }
            else {
                target = CodingJobLifecycleContract.Status.CANCELLED;
            }
        }

        CodingJobLifecycleContract.TransitionRequest transition =
                new CodingJobLifecycleContract.TransitionRequest(
                        CodingJobLifecycleContract.SCHEMA_VERSION,
                        request.expectedStateVersion(),
                        target,
                        null);
        byte[] transitionDigest = lifecycleDigester.transition(jobId, request.traceId(), transition);
        CodingJobLifecycleContract.JobResponse jobResponse;
        try {
            jobResponse = lifecycle.transition(
                    jobId,
                    request.traceId(),
                    "approval." + request.approvalId(),
                    transitionDigest,
                    transition);
        }
        finally {
            Arrays.fill(transitionDigest, (byte) 0);
        }

        CodingHandlerContract.ApprovalDecisionResponse response =
                new CodingHandlerContract.ApprovalDecisionResponse(
                        CodingHandlerContract.SCHEMA_VERSION,
                        jobId,
                        request.traceId(),
                        request.pipelineAttempt(),
                        request.approvalId(),
                        request.nodeId(),
                        request.stage(),
                        request.stageRound(),
                        request.candidateSha(),
                        request.validationHash(),
                        request.decision(),
                        actor.actorId(),
                        actor.role().name(),
                        jobResponse.stateVersion(),
                        jobResponse.status().name(),
                        nextAttempt,
                        now);
        int inserted = jdbc.update("""
                INSERT INTO app.coding_approval_decision (
                    job_id, pipeline_attempt, approval_id, trace_id, node_id,
                    stage, stage_round, decision, subject_candidate_sha, policy_hash,
                    validation_hash, feedback, actor_id, actor_role, idempotency_key,
                    request_digest, result_state_version, next_pipeline_attempt,
                    response_json, decided_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                """,
                jobId,
                request.pipelineAttempt(),
                request.approvalId(),
                request.traceId(),
                request.nodeId(),
                request.stage().name(),
                request.stageRound(),
                request.decision().name(),
                request.candidateSha(),
                job.policyHash(),
                request.validationHash(),
                request.feedback(),
                actor.actorId(),
                actor.role().name(),
                idempotencyKey,
                requestDigest,
                jobResponse.stateVersion(),
                nextAttempt,
                encode(response),
                Timestamp.from(now));
        if (inserted != 1) {
            throw unavailable();
        }
        return response;
    }

    private JobAuthority requireJob(UUID jobId, boolean lock) {
        String suffix = lock ? " FOR UPDATE" : " FOR SHARE";
        List<JobAuthority> rows = jdbc.query("""
                SELECT trace_id, actor_id, status, state_version, policy_hash
                FROM app.coding_job
                WHERE job_id = ? AND authority_source = 'SPRING_CONTROL_PLANE'
                """ + suffix,
                (rs, row) -> new JobAuthority(
                        rs.getObject("trace_id", UUID.class),
                        rs.getObject("actor_id", UUID.class),
                        rs.getString("status"),
                        rs.getInt("state_version"),
                        rs.getString("policy_hash")),
                jobId);
        if (rows.size() != 1) {
            throw failure("JOB_NOT_FOUND", "Authoritative Coding Job not found.", HttpStatus.NOT_FOUND);
        }
        return rows.get(0);
    }

    private AttemptAuthority requireActiveAttempt(UUID jobId, int pipelineAttempt) {
        List<AttemptAuthority> rows = jdbc.query("""
                SELECT pipeline_attempt
                FROM app.coding_pipeline_attempt
                WHERE job_id = ?
                ORDER BY pipeline_attempt DESC
                LIMIT 1
                FOR UPDATE
                """, (rs, row) -> new AttemptAuthority(rs.getInt("pipeline_attempt")), jobId);
        if (rows.size() != 1 || rows.get(0).pipelineAttempt() != pipelineAttempt) {
            throw conflict(
                    "PIPELINE_ATTEMPT_CONFLICT",
                    "The requested Coding pipeline attempt is not current.");
        }
        String attemptStatus = jdbc.queryForObject("""
                SELECT status FROM app.coding_pipeline_attempt
                WHERE job_id = ? AND pipeline_attempt = ?
                """, String.class, jobId, pipelineAttempt);
        if (!"ACTIVE".equals(attemptStatus)) {
            throw conflict(
                    "PIPELINE_ATTEMPT_CONFLICT",
                    "The requested Coding pipeline attempt is not active.");
        }
        return rows.get(0);
    }

    private ExistingRequest findRequest(UUID jobId) {
        List<ExistingRequest> rows = jdbc.query("""
                SELECT cjr.request_text, cjr.system_work_id, cjr.work_slug,
                       cjr.request_digest, cjr.created_at, cj.trace_id
                FROM app.coding_job_request cjr
                JOIN app.coding_job cj ON cj.job_id = cjr.job_id
                WHERE cjr.job_id = ?
                """, (rs, row) -> new ExistingRequest(
                        rs.getBytes("request_digest"),
                        new CodingHandlerContract.JobRequestResponse(
                                CodingHandlerContract.SCHEMA_VERSION,
                                jobId,
                                rs.getObject("trace_id", UUID.class),
                                rs.getString("request_text"),
                                rs.getString("system_work_id"),
                                rs.getString("work_slug"),
                                rs.getTimestamp("created_at").toInstant())),
                jobId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void ensureAttemptOne(UUID jobId) {
        jdbc.update("""
                INSERT INTO app.coding_pipeline_attempt (job_id, pipeline_attempt, status)
                VALUES (?, 1, 'ACTIVE')
                ON CONFLICT (job_id, pipeline_attempt) DO NOTHING
                """, jobId);
    }

    private ExistingDecision findDecisionReplay(UUID jobId, String idempotencyKey) {
        List<ExistingDecision> rows = jdbc.query("""
                SELECT request_digest, response_json::text
                FROM app.coding_approval_decision
                WHERE job_id = ? AND idempotency_key = ?
                """, (rs, row) -> new ExistingDecision(
                        rs.getBytes("request_digest"), rs.getString("response_json")),
                jobId, idempotencyKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private boolean approvalAlreadyExists(UUID jobId, UUID approvalId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM app.coding_approval_decision
                WHERE job_id = ? AND approval_id = ?
                """, Integer.class, jobId, approvalId);
        return count != null && count > 0;
    }

    private byte[] digest(String command, UUID jobId, UUID actorId, Object request) {
        byte[] encoded = null;
        try {
            encoded = objectMapper.writeValueAsBytes(List.of(command, jobId, actorId, request));
            return MessageDigest.getInstance("SHA-256").digest(encoded);
        }
        catch (JsonProcessingException | NoSuchAlgorithmException failure) {
            throw unavailable();
        }
        finally {
            if (encoded != null) {
                Arrays.fill(encoded, (byte) 0);
            }
        }
    }

    private String encode(CodingHandlerContract.ApprovalDecisionResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        }
        catch (JsonProcessingException failure) {
            throw unavailable();
        }
    }

    private CodingHandlerContract.ApprovalDecisionResponse decodeDecision(String value) {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        try {
            return objectMapper.readValue(
                    encoded, CodingHandlerContract.ApprovalDecisionResponse.class);
        }
        catch (IOException failure) {
            throw unavailable();
        }
        finally {
            Arrays.fill(encoded, (byte) 0);
        }
    }

    static void requireRole(AdminRole role, String requiredRole) {
        boolean permitted = switch (requiredRole) {
            case "GENERAL_ADMIN" -> role.isCmsAdministrator();
            case "SUPER_ADMIN" -> role.isPlatformGlobal();
            default -> false;
        };
        if (!permitted) {
            throw failure(
                    "APPROVAL_ROLE_FORBIDDEN",
                    "The authenticated role cannot decide this approval stage.",
                    HttpStatus.FORBIDDEN);
        }
    }

    static CodingJobLifecycleContract.CreateRequest authoritativeCreateRequest(
            AuthenticatedActor actor,
            CodingHandlerContract.CreateCodingJobRequest request) {
        return new CodingJobLifecycleContract.CreateRequest(
                request.schemaVersion(),
                request.profileVersionId(),
                actor.actorId(),
                request.projectId(),
                request.repositoryId(),
                request.graphStep(),
                request.baseSha(),
                request.contextDigest(),
                request.policyHash(),
                request.promptVersion(),
                request.allowedCapabilities(),
                request.allowedNodes(),
                request.expiresAt());
    }

    private static void requireIdempotencyKey(String value) {
        if (value == null || !IDEMPOTENCY_KEY.matcher(value).matches()) {
            throw failure(
                    "IDEMPOTENCY_KEY_INVALID",
                    "Idempotency-Key does not satisfy the Coding approval contract.",
                    HttpStatus.BAD_REQUEST);
        }
    }

    private static CodingJobLifecycleException conflict(String code, String message) {
        return failure(code, message, HttpStatus.CONFLICT);
    }

    private static CodingJobLifecycleException failure(
            String code, String message, HttpStatus status) {
        return new CodingJobLifecycleException(code, message, status);
    }

    private static CodingJobLifecycleException unavailable() {
        return new CodingJobLifecycleException(
                "CODING_HANDLER_STORE_UNAVAILABLE",
                "The Coding Handler command store is unavailable.",
                HttpStatus.SERVICE_UNAVAILABLE,
                true,
                1_000L);
    }

    private record JobAuthority(
            UUID traceId, UUID actorId, String status, int stateVersion, String policyHash) {
        boolean terminal() {
            return switch (status) {
                case "COMPLETED", "FAILED", "CANCELLED", "EXPIRED" -> true;
                default -> false;
            };
        }
    }

    private record AttemptAuthority(int pipelineAttempt) { }
    private record ExistingRequest(
            byte[] requestDigest, CodingHandlerContract.JobRequestResponse response) { }
    private record ExistingDecision(byte[] requestDigest, String responseJson) { }
}

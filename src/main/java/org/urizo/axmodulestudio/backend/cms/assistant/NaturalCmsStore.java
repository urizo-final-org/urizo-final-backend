package org.urizo.axmodulestudio.backend.cms.assistant;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.urizo.axmodulestudio.backend.auth.security.AuthenticatedActor;

@Service
@Profile("dev & local-full")
@ConditionalOnProperty(
        prefix = "ax.coding.model-turn-bridge", name = "enabled", havingValue = "true")
public final class NaturalCmsStore {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    NaturalCmsStore(
            @Qualifier("codingModelTurnJdbcTemplate") JdbcTemplate jdbc,
            @Qualifier("codingModelTurnTransactionTemplate") TransactionTemplate transactions,
            ObjectMapper objectMapper,
            Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc is required");
        this.transactions = Objects.requireNonNull(transactions, "transactions are required");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    public NaturalCmsContract.JobResponse create(
            AuthenticatedActor actor,
            UUID traceId,
            NaturalCmsContract.CreateJobRequest request) {
        if (actor == null || !actor.role().isCmsAdministrator()) {
            throw forbidden("A CMS administrator is required.");
        }
        UUID jobId = UUID.randomUUID();
        Instant now = clock.instant();
        NaturalCmsContract.JobResponse created = transactions.execute(status -> {
            requireActiveProfile(request.profileVersionId());
            jdbc.update("""
                    INSERT INTO app.natural_cms_job (
                        job_id, trace_id, profile_version_id, actor_id, request_text,
                        resource_type, resource_id, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    jobId,
                    traceId,
                    request.profileVersionId(),
                    actor.actorId(),
                    request.requestText(),
                    request.resource().type(),
                    request.resource().id(),
                    Timestamp.from(now),
                    Timestamp.from(now));
            return requireJob(jobId, false);
        });
        if (created == null) {
            throw unavailable();
        }
        return created;
    }

    public NaturalCmsContract.JobResponse get(
            String authorization, UUID jobId, int pipelineAttempt) {
        return authenticated(authorization, () -> {
            NaturalCmsContract.JobResponse job = requireJob(jobId, false);
            if (job.pipelineAttempt() != pipelineAttempt) {
                throw conflict("Natural CMS pipelineAttempt is not current.");
            }
            return job;
        });
    }

    public Optional<NaturalCmsContract.HandlerResult> findResult(
            String authorization, UUID jobId, int pipelineAttempt, UUID resultId) {
        return authenticated(authorization, () -> findResult(jobId, pipelineAttempt, resultId));
    }

    public NaturalCmsContract.HandlerResult record(
            String authorization,
            UUID jobId,
            int pipelineAttempt,
            NaturalCmsContract.StageExecutionResponse response) {
        return authenticated(authorization, () -> {
            NaturalCmsContract.JobResponse job = requireJob(jobId, true);
            if (job.pipelineAttempt() != pipelineAttempt
                    || !job.resource().equals(response.resource())) {
                throw conflict("Natural CMS result does not match its Job boundary.");
            }
            Optional<NaturalCmsContract.HandlerResult> existing =
                    findResult(jobId, pipelineAttempt, response.resultId());
            if (existing.isPresent()) {
                NaturalCmsContract.HandlerResult result = existing.get();
                if (!result.handlerKey().equals(response.handlerKey())
                        || !result.resultPort().equals(response.resultPort())) {
                    throw conflict("Natural CMS resultId is already bound.");
                }
                return result;
            }

            Instant recordedAt = clock.instant();
            jdbc.update("""
                    INSERT INTO app.natural_cms_handler_result (
                        result_id, job_id, pipeline_attempt, trace_id, handler_key,
                        result_port, resource_type, resource_id, structured_command,
                        preview_id, preview_hash, payload, recorded_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, CAST(? AS jsonb), ?)
                    """,
                    response.resultId(), jobId, pipelineAttempt, job.traceId(),
                    response.handlerKey(), response.resultPort(), response.resource().type(),
                    response.resource().id(), json(response.structuredCommand()),
                    response.previewId(), response.previewHash(), json(response.payload()),
                    Timestamp.from(recordedAt));
            updateJob(jobId, response, recordedAt);
            return findResult(jobId, pipelineAttempt, response.resultId()).orElseThrow();
        });
    }

    public NaturalCmsContract.JobResponse decide(
            AuthenticatedActor actor,
            UUID jobId,
            NaturalCmsContract.ApprovalDecisionRequest request) {
        if (actor == null || !actor.role().isCmsAdministrator()) {
            throw forbidden("A CMS administrator is required.");
        }
        NaturalCmsContract.JobResponse decided = transactions.execute(status -> {
            NaturalCmsContract.JobResponse job = requireJob(jobId, true);
            if (!"WAITING_APPROVAL".equals(job.status())
                    || !job.previewValid()
                    || !request.previewId().equals(job.previewId())
                    || !request.previewHash().equals(job.previewHash())) {
                throw conflict("Natural CMS approval is not bound to the current preview.");
            }
            if (job.approvalDecision() != null) {
                if (job.approvalDecision().equals(request.decision())
                        && Objects.equals(job.approvalFeedback(), request.feedback())) {
                    return job;
                }
                throw conflict("Natural CMS preview already has another decision.");
            }
            jdbc.update("""
                    UPDATE app.natural_cms_job
                    SET approval_decision = ?, approval_feedback = ?, approver_id = ?,
                        pipeline_attempt = pipeline_attempt +
                            CASE WHEN ? = 'REJECTED' AND pipeline_attempt < 3 THEN 1 ELSE 0 END,
                        state_version = state_version + 1, updated_at = ?
                    WHERE job_id = ? AND status = 'WAITING_APPROVAL'
                    """,
                    request.decision(), request.feedback(), actor.actorId(), request.decision(),
                    Timestamp.from(clock.instant()), jobId);
            return requireJob(jobId, false);
        });
        if (decided == null) {
            throw unavailable();
        }
        return decided;
    }

    boolean hasPreviewResult(UUID jobId, int pipelineAttempt) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM app.natural_cms_handler_result
                WHERE job_id = ? AND pipeline_attempt = ? AND handler_key = 'cms.preview'
                """, Integer.class, jobId, pipelineAttempt);
        return count != null && count > 0;
    }

    private void updateJob(
            UUID jobId,
            NaturalCmsContract.StageExecutionResponse response,
            Instant updatedAt) {
        switch (response.handlerKey()) {
            case "cms.analyze" -> jdbc.update("""
                    UPDATE app.natural_cms_job
                    SET status = 'ACTIVE', updated_at = ?
                    WHERE job_id = ? AND status <> 'COMPLETED'
                    """, Timestamp.from(updatedAt), jobId);
            case "cms.preview" -> jdbc.update("""
                    UPDATE app.natural_cms_job
                    SET status = 'WAITING_APPROVAL', structured_command = CAST(? AS jsonb),
                        preview_id = ?, preview_hash = ?, preview_payload = CAST(? AS jsonb),
                        preview_valid = TRUE, approval_decision = NULL,
                        approval_feedback = NULL, approver_id = NULL,
                        updated_at = ?
                    WHERE job_id = ? AND status <> 'COMPLETED'
                    """,
                    json(response.structuredCommand()), response.previewId(),
                    response.previewHash(), json(response.payload()),
                    Timestamp.from(updatedAt), jobId);
            case "cms.discard" -> {
                boolean retry = "retry".equals(response.resultPort());
                jdbc.update("""
                        UPDATE app.natural_cms_job
                        SET status = ?, preview_valid = FALSE, updated_at = ?
                        WHERE job_id = ? AND approval_decision = 'REJECTED'
                        """,
                        retry ? "ACTIVE" : "REJECTED",
                        Timestamp.from(updatedAt), jobId);
            }
            case "cms.apply" -> jdbc.update("""
                    UPDATE app.natural_cms_job
                    SET status = 'COMPLETED', preview_valid = FALSE,
                        updated_at = ?
                    WHERE job_id = ? AND approval_decision = 'APPROVED'
                    """, Timestamp.from(updatedAt), jobId);
            default -> throw new IllegalArgumentException("Natural CMS handler is not registered.");
        }
    }

    private Optional<NaturalCmsContract.HandlerResult> findResult(
            UUID jobId, int pipelineAttempt, UUID resultId) {
        List<NaturalCmsContract.HandlerResult> rows = jdbc.query("""
                SELECT result_id, job_id, trace_id, pipeline_attempt, handler_key, result_port,
                       resource_type, resource_id, structured_command, preview_id, preview_hash,
                       payload, recorded_at
                FROM app.natural_cms_handler_result
                WHERE job_id = ? AND pipeline_attempt = ? AND result_id = ?
                """,
                (rs, row) -> new NaturalCmsContract.HandlerResult(
                        rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class), rs.getInt(4), rs.getString(5),
                        rs.getString(6), new NaturalCmsContract.ResourceRef(
                                rs.getString(7), rs.getString(8)),
                        parse(rs.getString(9)), rs.getObject(10, UUID.class),
                        rs.getString(11), parse(rs.getString(12)),
                        rs.getTimestamp(13).toInstant()),
                jobId, pipelineAttempt, resultId);
        return rows.stream().findFirst();
    }

    private NaturalCmsContract.JobResponse requireJob(UUID jobId, boolean lock) {
        String sql = """
                SELECT job_id, trace_id, profile_version_id, pipeline_attempt, state_version,
                       status, request_text, resource_type, resource_id, structured_command,
                       preview_id, preview_hash, preview_valid, approval_decision,
                       approval_feedback, created_at, updated_at
                FROM app.natural_cms_job WHERE job_id = ?
                """ + (lock ? " FOR UPDATE" : "");
        List<NaturalCmsContract.JobResponse> rows = jdbc.query(sql,
                (rs, row) -> new NaturalCmsContract.JobResponse(
                        NaturalCmsContract.SCHEMA_VERSION,
                        rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class), rs.getInt(4), rs.getInt(5),
                        rs.getString(6), rs.getString(7),
                        new NaturalCmsContract.ResourceRef(rs.getString(8), rs.getString(9)),
                        parse(rs.getString(10)), rs.getObject(11, UUID.class),
                        rs.getString(12), rs.getBoolean(13), rs.getString(14),
                        rs.getString(15), rs.getTimestamp(16).toInstant(),
                        rs.getTimestamp(17).toInstant()),
                jobId);
        if (rows.size() != 1) {
            throw new NaturalCmsException(
                    "NATURAL_CMS_JOB_NOT_FOUND", "Natural CMS Job was not found.",
                    HttpStatus.NOT_FOUND);
        }
        return rows.get(0);
    }

    private void requireActiveProfile(UUID profileVersionId) {
        List<String> profiles = jdbc.query("""
                SELECT profile_key || ':' || status
                FROM app.ai_profile_version WHERE profile_version_id = ?
                """, (rs, row) -> rs.getString(1), profileVersionId);
        if (!profiles.equals(List.of("NATURAL_CMS:ACTIVE"))) {
            throw new NaturalCmsException(
                    "PROFILE_VERSION_NOT_ACTIVE",
                    "An ACTIVE NATURAL_CMS Profile Version is required.",
                    HttpStatus.CONFLICT);
        }
    }

    private <T> T authenticated(String authorization, java.util.function.Supplier<T> action) {
        byte[] digest = credentialDigest(authorization);
        try {
            T value = transactions.execute(status -> {
                authenticate(digest);
                return action.get();
            });
            if (value == null) {
                throw unavailable();
            }
            return value;
        }
        finally {
            Arrays.fill(digest, (byte) 0);
        }
    }

    private void authenticate(byte[] credentialDigest) {
        Instant now = clock.instant();
        List<UUID> matches = jdbc.query("""
                SELECT credential_id FROM app.coding_service_credential
                WHERE credential_digest = ? AND status IN ('ACTIVE', 'RETIRING')
                  AND valid_from <= ? AND (valid_until IS NULL OR valid_until > ?)
                FOR UPDATE
                """,
                (rs, row) -> rs.getObject(1, UUID.class),
                credentialDigest, Timestamp.from(now), Timestamp.from(now));
        if (matches.size() != 1) {
            throw new NaturalCmsException(
                    "SERVICE_AUTHENTICATION_FAILED", "Service authentication failed.",
                    HttpStatus.UNAUTHORIZED);
        }
        jdbc.update("""
                UPDATE app.coding_service_credential SET last_used_at = ?
                WHERE credential_id = ?
                """, Timestamp.from(now), matches.get(0));
    }

    private String json(JsonNode value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        }
        catch (JsonProcessingException failure) {
            throw new NaturalCmsException(
                    "CONTRACT_VALIDATION_FAILED", "Natural CMS JSON is invalid.",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    private JsonNode parse(String value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.readTree(value);
        }
        catch (JsonProcessingException failure) {
            throw new IllegalStateException("Stored Natural CMS JSON is invalid.", failure);
        }
    }

    private static byte[] credentialDigest(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")
                || authorization.length() <= 7) {
            throw new NaturalCmsException(
                    "SERVICE_AUTHENTICATION_FAILED", "Service authentication failed.",
                    HttpStatus.UNAUTHORIZED);
        }
        byte[] token = authorization.substring(7).getBytes(StandardCharsets.UTF_8);
        try {
            return sha256(token);
        }
        finally {
            Arrays.fill(token, (byte) 0);
        }
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        }
        catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable.", failure);
        }
    }

    private static NaturalCmsException conflict(String message) {
        return new NaturalCmsException("NATURAL_CMS_STATE_CONFLICT", message, HttpStatus.CONFLICT);
    }

    private static NaturalCmsException forbidden(String message) {
        return new NaturalCmsException("FORBIDDEN", message, HttpStatus.FORBIDDEN);
    }

    private static NaturalCmsException unavailable() {
        return new NaturalCmsException(
                "NATURAL_CMS_STORE_UNAVAILABLE",
                "Natural CMS result store is unavailable.",
                HttpStatus.SERVICE_UNAVAILABLE,
                true);
    }
}

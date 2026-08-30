package org.urizo.axmodulestudio.backend.coding.service;

import org.urizo.axmodulestudio.backend.coding.dto.CodingWorkerContract;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Array;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@ConditionalOnProperty(prefix = "ax.coding.model-turn-bridge", name = "enabled", havingValue = "true")
public final class CodingWorkerService {

    private static final Duration LEASE_DURATION = Duration.ofSeconds(30);
    private static final Duration TURN_DEADLINE = Duration.ofMinutes(2);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    CodingWorkerService(
            @Qualifier("codingModelTurnJdbcTemplate") JdbcTemplate jdbc,
            @Qualifier("codingModelTurnTransactionTemplate") TransactionTemplate transactions,
            ObjectMapper objectMapper,
            Clock clock) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public CodingWorkerContract.ClaimResponse claim(
            String authorization, CodingWorkerContract.ClaimRequest request) {
        return command(authorization, "CLAIM", request.jobId(), request.idempotencyKey(),
                request, CodingWorkerContract.ClaimResponse.class,
                () -> claimCurrent(request));
    }

    public CodingWorkerContract.ClaimContextResponse claimContext(
            String authorization, UUID jobId) {
        Objects.requireNonNull(jobId, "jobId is required");
        byte[] credentialDigest = credentialDigest(authorization);
        try {
            CodingWorkerContract.ClaimContextResponse result = transactions.execute(status -> {
                authenticate(credentialDigest);
                JobRow job = requireJob(jobId);
                UUID profileVersionId = requireProfileVersionId(job);
                ClaimSource source = claimSource(
                        job.status(), job.stateVersion(), job.workerAttempt(), job.leaseId());
                return new CodingWorkerContract.ClaimContextResponse(
                        version(),
                        claimEventId(jobId, source.stateVersion()),
                        "CODING_JOB_REQUESTED",
                        jobId,
                        job.traceId(),
                        claimIdempotencyKey(jobId, source.stateVersion()),
                        source.attempt(),
                        source.stateVersion(),
                        job.createdAt(),
                        profileVersionId,
                        1,
                        source.attempt(),
                        null,
                        null,
                        new CodingWorkerContract.JobPayload(
                                job.actorId(), job.projectId(), job.repositoryId(),
                                job.graphStep(), job.baseSha(), job.contextDigest(),
                                job.policyHash(), job.expiresAt()));
            });
            if (result == null) {
                throw unavailable();
            }
            return result;
        }
        finally {
            Arrays.fill(credentialDigest, (byte) 0);
        }
    }

    public CodingWorkerContract.HeartbeatResponse heartbeat(
            String authorization, CodingWorkerContract.HeartbeatRequest request) {
        return command(authorization, "HEARTBEAT", request.jobId(), request.idempotencyKey(),
                request, CodingWorkerContract.HeartbeatResponse.class,
                () -> heartbeatCurrent(request));
    }

    public CodingWorkerContract.OutcomeResponse outcome(
            String authorization, CodingWorkerContract.OutcomeRequest request) {
        return command(authorization, "OUTCOME", request.jobId(), request.idempotencyKey(),
                request, CodingWorkerContract.OutcomeResponse.class,
                () -> outcomeCurrent(request));
    }

    private CodingWorkerContract.ClaimResponse claimCurrent(
            CodingWorkerContract.ClaimRequest request) {
        JobRow job = requireJob(request.jobId());
        UUID profileVersionId = requireProfileVersionId(job);
        Instant now = Instant.now(clock);
        requireCorrelation(job, request.traceId(), request.expectedStateVersion());
        if (!"PENDING".equals(job.status()) && !"RUNNING".equals(job.status())) {
            throw conflict("JOB_STATE_VERSION_CONFLICT", "Coding job is not claimable.");
        }
        if (!job.expiresAt().isAfter(now)) {
            throw conflict("JOB_EXPIRED", "Coding job has expired.");
        }
        if (job.nextAttemptAt().isAfter(now)) {
            long retry = Math.max(1L, Duration.between(now, job.nextAttemptAt()).toMillis());
            throw new CodingWorkerException(
                    "IDEMPOTENCY_IN_PROGRESS", "Coding job retry is not due.",
                    HttpStatus.CONFLICT, true, retry);
        }
        if (job.leaseId() != null && job.leaseExpiresAt().isAfter(now)) {
            long retry = Math.max(1L, Duration.between(now, job.leaseExpiresAt()).toMillis());
            throw new CodingWorkerException(
                    "IDEMPOTENCY_IN_PROGRESS", "Coding job has an active worker lease.",
                    HttpStatus.CONFLICT, true, retry);
        }
        if (job.workerAttempt() >= job.workerMaxAttempts()) {
            throw conflict("JOB_STATE_VERSION_CONFLICT", "Coding job exhausted its worker attempts.");
        }
        UUID leaseId = UUID.randomUUID();
        Instant leaseExpiresAt = boundedLeaseExpiry(now, job.expiresAt());
        int nextVersion = job.stateVersion() + 1;
        int nextAttempt = job.workerAttempt() + 1;
        int updated = jdbc.update("UPDATE app.coding_job SET status = 'RUNNING', "
                        + "state_version = ?, worker_attempt = ?, worker_lease_id = ?, "
                        + "worker_lease_expires_at = ?, last_heartbeat_at = ?, "
                        + "started_at = COALESCE(started_at, ?), updated_at = ? WHERE job_id = ?",
                nextVersion, nextAttempt, leaseId, Timestamp.from(leaseExpiresAt),
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), request.jobId());
        if (updated != 1) {
            throw unavailable();
        }
        Instant deadline = job.expiresAt().isBefore(now.plus(TURN_DEADLINE))
                ? job.expiresAt() : now.plus(TURN_DEADLINE);
        UUID approvalId = UUID.nameUUIDFromBytes(
                (request.jobId() + ":approval").getBytes(StandardCharsets.UTF_8));
        CodingWorkerContract.Snapshot snapshot = new CodingWorkerContract.Snapshot(
                new CodingWorkerContract.Actor(job.actorId(), "DEVELOPER"),
                new CodingWorkerContract.Project(job.projectId()),
                new CodingWorkerContract.Repository(job.repositoryId()),
                job.graphStep(), job.baseSha(), job.contextDigest(), job.policyHash(),
                job.promptVersion(), job.allowedCapabilities(), job.allowedNodes(), deadline,
                "You are the local AX Module Studio coding graph. Use only Spring-authorized tools.",
                "Read README.md and summarize the approved local coding fixture for graph step "
                        + job.graphStep() + ".",
                "README.md", approvalId);
        return new CodingWorkerContract.ClaimResponse(
                version(), request.jobId(), request.traceId(), profileVersionId, leaseId,
                leaseExpiresAt, nextVersion, job.workerAttempt() > 0 || job.stateVersion() > 2,
                snapshot);
    }

    private CodingWorkerContract.HeartbeatResponse heartbeatCurrent(
            CodingWorkerContract.HeartbeatRequest request) {
        JobRow job = requireJob(request.jobId());
        Instant now = Instant.now(clock);
        requireLease(job, request.traceId(), request.expectedStateVersion(), request.leaseId(), now);
        Instant expires = boundedLeaseExpiry(now, job.expiresAt());
        int updated = jdbc.update("UPDATE app.coding_job SET worker_lease_expires_at = ?, "
                        + "last_heartbeat_at = ?, updated_at = ? WHERE job_id = ? "
                        + "AND worker_lease_id = ? AND state_version = ? AND status = 'RUNNING'",
                Timestamp.from(expires), Timestamp.from(now), Timestamp.from(now),
                request.jobId(), request.leaseId(), request.expectedStateVersion());
        if (updated != 1) {
            throw conflict("JOB_STATE_VERSION_CONFLICT", "Coding job worker lease is no longer current.");
        }
        return new CodingWorkerContract.HeartbeatResponse(
                version(), request.jobId(), request.traceId(), request.leaseId(),
                expires, request.expectedStateVersion());
    }

    private CodingWorkerContract.OutcomeResponse outcomeCurrent(
            CodingWorkerContract.OutcomeRequest request) {
        JobRow job = requireJob(request.jobId());
        Instant now = Instant.now(clock);
        requireLease(job, request.traceId(), request.expectedStateVersion(), request.leaseId(), now);
        int nextVersion = job.stateVersion() + 1;
        String status;
        switch (request.outcome()) {
            case "WAITING_APPROVAL" -> {
                status = "WAITING_APPROVAL";
                updateOutcome(request, status, nextVersion, now, null, null, null, null);
            }
            case "COMPLETED" -> {
                status = "COMPLETED";
                updateOutcome(request, status, nextVersion, now, now, null, null, null);
            }
            case "RETRYABLE_FAILURE" -> {
                if (job.workerAttempt() < job.workerMaxAttempts()) {
                    status = "PENDING";
                    long delaySeconds = Math.min(30L, 1L << Math.min(5, job.workerAttempt()));
                    updateOutcome(request, status, nextVersion, now, null,
                            now.plusSeconds(delaySeconds), null, null);
                }
                else {
                    status = "FAILED";
                    updateOutcome(request, status, nextVersion, now, now,
                            null, request.errorCode(), true);
                }
            }
            case "PERMANENT_FAILURE" -> {
                status = "FAILED";
                updateOutcome(request, status, nextVersion, now, now,
                        null, request.errorCode(), false);
            }
            default -> throw new IllegalArgumentException("Unsupported outcome.");
        }
        return new CodingWorkerContract.OutcomeResponse(
                version(), request.jobId(), request.traceId(), nextVersion, status);
    }

    private void updateOutcome(
            CodingWorkerContract.OutcomeRequest request,
            String status,
            int nextVersion,
            Instant now,
            Instant finishedAt,
            Instant nextAttemptAt,
            String failureCode,
            Boolean retryable) {
        int updated = jdbc.update("UPDATE app.coding_job SET status = ?, state_version = ?, "
                        + "worker_lease_id = NULL, worker_lease_expires_at = NULL, "
                        + "last_heartbeat_at = ?, updated_at = ?, finished_at = ?, "
                        + "started_at = CASE WHEN ? = 'PENDING' THEN NULL ELSE started_at END, "
                        + "next_attempt_at = COALESCE(?, next_attempt_at), "
                        + "failure_code = ?, failure_retryable = ? "
                        + "WHERE job_id = ? AND status = 'RUNNING' "
                        + "AND state_version = ? AND worker_lease_id = ?",
                status, nextVersion, Timestamp.from(now), Timestamp.from(now),
                finishedAt == null ? null : Timestamp.from(finishedAt), status,
                nextAttemptAt == null ? null : Timestamp.from(nextAttemptAt),
                failureCode, retryable, request.jobId(), request.expectedStateVersion(), request.leaseId());
        if (updated != 1) {
            throw conflict("JOB_STATE_VERSION_CONFLICT", "Coding job worker lease is no longer current.");
        }
    }

    private <T> T command(
            String authorization,
            String type,
            UUID jobId,
            String idempotencyKey,
            Object request,
            Class<T> responseType,
            Supplier<T> action) {
        byte[] credentialDigest = credentialDigest(authorization);
        byte[] requestDigest = requestDigest(request);
        try {
            T result = transactions.execute(status -> {
                authenticate(credentialDigest);
                jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                        resultSet -> { },
                        "WORKER:" + type + ":" + jobId + ":" + idempotencyKey);
                List<StoredCommand> existing = jdbc.query(
                        "SELECT request_digest, response_json::text FROM app.coding_worker_command "
                                + "WHERE command_type = ? AND job_id = ? AND idempotency_key = ?",
                        (rs, row) -> new StoredCommand(rs.getBytes(1), rs.getString(2)),
                        type, jobId, idempotencyKey);
                if (!existing.isEmpty()) {
                    if (!MessageDigest.isEqual(requestDigest, existing.get(0).digest())) {
                        throw conflict("IDEMPOTENCY_KEY_REUSED",
                                "Worker idempotency key was reused with another request.");
                    }
                    T replay = decode(existing.get(0).response(), responseType);
                    if (request instanceof CodingWorkerContract.ClaimRequest claimRequest
                            && replay instanceof CodingWorkerContract.ClaimResponse claimResponse
                            && !claimResponse.leaseExpiresAt().isAfter(Instant.now(clock))) {
                        CodingWorkerContract.ClaimResponse renewed =
                                renewExpiredClaim(claimRequest, claimResponse);
                        jdbc.update("UPDATE app.coding_worker_command SET response_json = ?::jsonb "
                                        + "WHERE command_type = 'CLAIM' AND job_id = ? "
                                        + "AND idempotency_key = ?",
                                encode(renewed), jobId, idempotencyKey);
                        return responseType.cast(renewed);
                    }
                    return replay;
                }
                T response = action.get();
                jdbc.update("INSERT INTO app.coding_worker_command "
                                + "(command_id, command_type, job_id, idempotency_key, request_digest, response_json) "
                                + "VALUES (?, ?, ?, ?, ?, ?::jsonb)",
                        UUID.randomUUID(), type, jobId, idempotencyKey,
                        requestDigest, encode(response));
                return response;
            });
            if (result == null) {
                throw unavailable();
            }
            return result;
        }
        finally {
            Arrays.fill(credentialDigest, (byte) 0);
            Arrays.fill(requestDigest, (byte) 0);
        }
    }

    private CodingWorkerContract.ClaimResponse renewExpiredClaim(
            CodingWorkerContract.ClaimRequest request,
            CodingWorkerContract.ClaimResponse previous) {
        JobRow job = requireJob(request.jobId());
        Instant now = Instant.now(clock);
        boolean bound = previous.jobId().equals(request.jobId())
                && previous.traceId().equals(request.traceId())
                && job.traceId().equals(request.traceId())
                && "RUNNING".equals(job.status())
                && job.stateVersion() == previous.stateVersion()
                && Objects.equals(job.leaseId(), previous.leaseId())
                && job.leaseExpiresAt() != null
                && !job.leaseExpiresAt().isAfter(now)
                && previous.snapshot().deadlineAt().isAfter(now)
                && job.expiresAt().isAfter(now);
        if (!bound || job.workerAttempt() >= job.workerMaxAttempts()) {
            throw conflict("JOB_STATE_VERSION_CONFLICT",
                    "Expired coding claim cannot be renewed.");
        }
        Instant renewedUntil = boundedLeaseExpiry(now, job.expiresAt());
        int updated = jdbc.update("UPDATE app.coding_job SET "
                        + "worker_lease_expires_at = ?, last_heartbeat_at = ?, updated_at = ? "
                        + "WHERE job_id = ? AND status = 'RUNNING' AND state_version = ? "
                        + "AND worker_lease_id = ? AND worker_lease_expires_at <= ?",
                Timestamp.from(renewedUntil), Timestamp.from(now), Timestamp.from(now),
                request.jobId(), previous.stateVersion(), previous.leaseId(), Timestamp.from(now));
        if (updated != 1) {
            throw conflict("JOB_STATE_VERSION_CONFLICT",
                    "Expired coding claim is no longer current.");
        }
        return new CodingWorkerContract.ClaimResponse(
                previous.schemaVersion(), previous.jobId(), previous.traceId(),
                previous.profileVersionId(), previous.leaseId(),
                renewedUntil, previous.stateVersion(), true, previous.snapshot());
    }

    private void authenticate(byte[] credentialDigest) {
        Instant now = Instant.now(clock);
        List<UUID> matches = jdbc.query(
                "SELECT credential_id FROM app.coding_service_credential "
                        + "WHERE credential_digest = ? AND status IN ('ACTIVE', 'RETIRING') "
                        + "AND valid_from <= ? AND (valid_until IS NULL OR valid_until > ?) FOR UPDATE",
                (rs, row) -> rs.getObject(1, UUID.class),
                credentialDigest, Timestamp.from(now), Timestamp.from(now));
        if (matches.size() != 1) {
            throw new CodingWorkerException(
                    "SERVICE_AUTHENTICATION_FAILED", "Service authentication failed.",
                    HttpStatus.UNAUTHORIZED);
        }
        jdbc.update("UPDATE app.coding_service_credential SET last_used_at = ? "
                + "WHERE credential_id = ?", Timestamp.from(now), matches.get(0));
    }

    private JobRow requireJob(UUID jobId) {
        List<JobRow> rows = jdbc.query(
                "SELECT trace_id, profile_version_id, status, state_version, "
                        + "actor_id, project_id, repository_id, "
                        + "graph_step, base_sha, context_digest, policy_hash, prompt_version, "
                        + "allowed_capabilities, allowed_nodes, expires_at, worker_attempt, "
                        + "worker_max_attempts, next_attempt_at, worker_lease_id, "
                        + "worker_lease_expires_at, created_at "
                        + "FROM app.coding_job WHERE job_id = ? "
                        + "AND authority_source = 'SPRING_CONTROL_PLANE' FOR UPDATE",
                (rs, row) -> new JobRow(
                        rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getString(3), rs.getInt(4),
                        rs.getObject(5, UUID.class), rs.getObject(6, UUID.class),
                        rs.getObject(7, UUID.class), rs.getString(8), rs.getString(9),
                        rs.getString(10), rs.getString(11), rs.getString(12),
                        sqlStrings(rs.getArray(13)), sqlStrings(rs.getArray(14)),
                        rs.getTimestamp(15).toInstant(), rs.getInt(16), rs.getInt(17),
                        rs.getTimestamp(18).toInstant(), rs.getObject(19, UUID.class),
                        rs.getTimestamp(20) == null ? null : rs.getTimestamp(20).toInstant(),
                        rs.getTimestamp(21).toInstant()),
                jobId);
        if (rows.isEmpty()) {
            throw new CodingWorkerException(
                    "JOB_NOT_FOUND", "Authoritative coding job not found.",
                    HttpStatus.NOT_FOUND);
        }
        return rows.get(0);
    }

    private static UUID requireProfileVersionId(JobRow job) {
        if (job.profileVersionId() == null) {
            throw conflict(
                    "JOB_STATE_VERSION_CONFLICT",
                    "Coding job has no immutable Profile Version binding.");
        }
        return job.profileVersionId();
    }

    static UUID claimEventId(UUID jobId, int stateVersion) {
        return UUID.nameUUIDFromBytes((jobId + ":coding-requested:v" + stateVersion)
                .getBytes(StandardCharsets.UTF_8));
    }

    static String claimIdempotencyKey(UUID jobId, int stateVersion) {
        return "coding-job:" + jobId + ":v" + stateVersion;
    }

    static ClaimSource claimSource(
            String status, int stateVersion, int workerAttempt, UUID leaseId) {
        boolean claimed = "RUNNING".equals(status) && leaseId != null;
        if (!("PENDING".equals(status) || "RUNNING".equals(status))
                || ("PENDING".equals(status) && leaseId != null)
                || stateVersion < 1 || workerAttempt < 0
                || (claimed && (stateVersion < 2 || workerAttempt < 1))) {
            throw conflict(
                    "JOB_STATE_VERSION_CONFLICT",
                    "Coding job worker state cannot reconstruct a valid claim source.");
        }
        return claimed
                ? new ClaimSource(stateVersion - 1, workerAttempt)
                : new ClaimSource(stateVersion, workerAttempt + 1);
    }

    private static void requireCorrelation(JobRow job, UUID traceId, int stateVersion) {
        if (!job.traceId().equals(traceId)) {
            throw conflict("SERVICE_AUTHORIZATION_DENIED", "Coding job trace context does not match.");
        }
        if (job.stateVersion() != stateVersion) {
            throw conflict("JOB_STATE_VERSION_CONFLICT", "Coding job state version has changed.");
        }
    }

    private static void requireLease(
            JobRow job, UUID traceId, int version, UUID leaseId, Instant now) {
        requireCorrelation(job, traceId, version);
        if (!leaseIsCurrent(job.status(), job.leaseId(), job.leaseExpiresAt(),
                job.expiresAt(), leaseId, now)) {
            throw conflict("JOB_STATE_VERSION_CONFLICT", "Coding job worker lease is no longer current.");
        }
    }

    static Instant boundedLeaseExpiry(Instant now, Instant jobExpiresAt) {
        Instant leaseExpiresAt = now.plus(LEASE_DURATION);
        return jobExpiresAt.isBefore(leaseExpiresAt) ? jobExpiresAt : leaseExpiresAt;
    }

    static boolean leaseIsCurrent(
            String status,
            UUID currentLeaseId,
            Instant leaseExpiresAt,
            Instant jobExpiresAt,
            UUID expectedLeaseId,
            Instant now) {
        return "RUNNING".equals(status)
                && Objects.equals(currentLeaseId, expectedLeaseId)
                && leaseExpiresAt != null
                && leaseExpiresAt.isAfter(now)
                && jobExpiresAt.isAfter(now);
    }

    private byte[] credentialDigest(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")
                || authorization.length() <= 7) {
            throw new CodingWorkerException(
                    "SERVICE_AUTHENTICATION_FAILED", "Service authentication failed.",
                    HttpStatus.UNAUTHORIZED);
        }
        byte[] token = authorization.substring(7).getBytes(StandardCharsets.UTF_8);
        try {
            return sha256Bytes(token);
        }
        finally {
            Arrays.fill(token, (byte) 0);
        }
    }

    private byte[] requestDigest(Object request) {
        try {
            return sha256Bytes(objectMapper.writeValueAsBytes(request));
        }
        catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("Cannot encode worker request.", failure);
        }
    }

    private static byte[] sha256Bytes(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        }
        catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable.", failure);
        }
    }

    private String encode(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException failure) {
            throw new IllegalStateException("Cannot encode worker response.", failure);
        }
    }

    private <T> T decode(String value, Class<T> type) {
        try { return objectMapper.readValue(value, type); }
        catch (JsonProcessingException failure) {
            throw new IllegalStateException("Stored worker response is invalid.", failure);
        }
    }

    private static List<String> sqlStrings(Array array) throws SQLException {
        return List.of((String[]) array.getArray());
    }

    private static String version() { return CodingWorkerContract.SCHEMA_VERSION; }

    private static CodingWorkerException conflict(String code, String message) {
        return new CodingWorkerException(code, message, HttpStatus.CONFLICT);
    }

    private static CodingWorkerException unavailable() {
        return new CodingWorkerException(
                "INTERNAL_TRANSIENT_ERROR",
                "The authoritative coding worker store is unavailable.",
                HttpStatus.SERVICE_UNAVAILABLE, true, 1_000L);
    }

    private record StoredCommand(byte[] digest, String response) { }
    record ClaimSource(int stateVersion, int attempt) { }
    private record JobRow(
            UUID traceId, UUID profileVersionId, String status, int stateVersion,
            UUID actorId, UUID projectId, UUID repositoryId,
            String graphStep, String baseSha, String contextDigest, String policyHash,
            String promptVersion, List<String> allowedCapabilities, List<String> allowedNodes,
            Instant expiresAt, int workerAttempt, int workerMaxAttempts,
            Instant nextAttemptAt, UUID leaseId, Instant leaseExpiresAt, Instant createdAt) { }
}

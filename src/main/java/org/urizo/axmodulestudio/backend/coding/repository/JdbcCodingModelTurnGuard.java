package org.urizo.axmodulestudio.backend.coding.repository;

import org.urizo.axmodulestudio.backend.coding.dto.CodingModelTurnContract;
import org.urizo.axmodulestudio.backend.coding.dto.CodingModelTurnPermit;
import org.urizo.axmodulestudio.backend.coding.service.CodingModelTurnAccessException;
import org.urizo.axmodulestudio.backend.coding.service.CodingModelTurnRequestDigester;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Array;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

public final class JdbcCodingModelTurnGuard implements CodingModelTurnGuard {

    private static final String FIND_CREDENTIAL = """
            SELECT credential_id
            FROM app.coding_service_credential
            WHERE credential_digest = ?
              AND status IN ('ACTIVE', 'RETIRING')
              AND valid_from <= ?
              AND (valid_until IS NULL OR valid_until > ?)
            FOR UPDATE
            """;
    private static final String FIND_JOB = """
            SELECT trace_id, authority_source, status, state_version, context_digest, prompt_version,
                   allowed_capabilities, allowed_nodes, expires_at
            FROM app.coding_job
            WHERE job_id = ?
            FOR SHARE
            """;
    private static final String FIND_IDEMPOTENCY = """
            SELECT turn_id, request_digest, attempt, expected_state_version, status,
                   lease_id, lease_expires_at, response_json, failure_code, retryable
            FROM app.coding_model_turn_idempotency
            WHERE job_id = ? AND idempotency_key = ?
            FOR UPDATE
            """;

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final CodingModelTurnRequestDigester requestDigester;
    private final Clock clock;
    private final Duration leaseDuration;

    public JdbcCodingModelTurnGuard(
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate,
            ObjectMapper objectMapper,
            Clock clock,
            Duration leaseDuration) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate is required");
        this.transactionTemplate = Objects.requireNonNull(transactionTemplate, "transactionTemplate is required");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is required");
        this.requestDigester = new CodingModelTurnRequestDigester(objectMapper);
        this.clock = Objects.requireNonNull(clock, "clock is required");
        this.leaseDuration = Objects.requireNonNull(leaseDuration, "leaseDuration is required");
    }

    @Override
    public CodingModelTurnPermit reserve(
            String authorizationHeader,
            CodingModelTurnContract.Request request) {
        Objects.requireNonNull(request, "request is required");
        byte[] credentialDigest = credentialDigest(authorizationHeader);
        byte[] requestDigest = requestDigester.digest(request);
        try {
            CodingModelTurnPermit permit = transactionTemplate.execute(status ->
                    reserveInTransaction(credentialDigest, requestDigest, request));
            if (permit == null) {
                throw transientFailure();
            }
            return permit;
        }
        catch (CodingModelTurnAccessException failure) {
            throw failure;
        }
        catch (DataAccessException failure) {
            throw transientFailure();
        }
        finally {
            Arrays.fill(credentialDigest, (byte) 0);
            Arrays.fill(requestDigest, (byte) 0);
        }
    }

    @Override
    public void complete(CodingModelTurnPermit permit, CodingModelTurnContract.Response response) {
        requireActivePermit(permit);
        Objects.requireNonNull(response, "response is required");
        if (!permit.jobId().equals(response.jobId())
                || !permit.idempotencyKey().equals(response.idempotencyKey())) {
            throw new CodingModelTurnAccessException(
                    "CONTRACT_CORRELATION_MISMATCH",
                    "Model Turn completion correlation is invalid.",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        final String responseJson;
        try {
            responseJson = objectMapper.writeValueAsString(response);
        }
        catch (JsonProcessingException failure) {
            throw transientFailure();
        }
        try {
            OffsetDateTime completedAt = databaseTime(clock.instant());
            Integer updated = transactionTemplate.execute(status -> jdbcTemplate.update("""
                    UPDATE app.coding_model_turn_idempotency
                    SET status = 'COMPLETED',
                        response_json = CAST(? AS jsonb),
                        failure_code = NULL,
                        retryable = NULL,
                        updated_at = ?,
                        completed_at = ?
                    WHERE job_id = ?
                      AND idempotency_key = ?
                      AND lease_id = ?
                      AND status = 'IN_PROGRESS'
                    """,
                    responseJson,
                    completedAt,
                    completedAt,
                    permit.jobId(),
                    permit.idempotencyKey(),
                    permit.leaseId()));
            if (updated == null || updated != 1) {
                throw stateConflict("Model Turn completion lease is no longer current.");
            }
        }
        catch (CodingModelTurnAccessException failure) {
            throw failure;
        }
        catch (DataAccessException failure) {
            throw transientFailure();
        }
    }

    @Override
    public void fail(CodingModelTurnPermit permit, String failureCode, boolean retryable) {
        requireActivePermit(permit);
        if (failureCode == null || failureCode.isBlank() || failureCode.length() > 120) {
            throw new IllegalArgumentException("failureCode is invalid");
        }
        try {
            OffsetDateTime updatedAt = databaseTime(clock.instant());
            Integer updated = transactionTemplate.execute(status -> jdbcTemplate.update("""
                    UPDATE app.coding_model_turn_idempotency
                    SET status = 'FAILED',
                        response_json = NULL,
                        failure_code = ?,
                        retryable = ?,
                        updated_at = ?,
                        completed_at = NULL
                    WHERE job_id = ?
                      AND idempotency_key = ?
                      AND lease_id = ?
                      AND status = 'IN_PROGRESS'
                    """,
                    failureCode,
                    retryable,
                    updatedAt,
                    permit.jobId(),
                    permit.idempotencyKey(),
                    permit.leaseId()));
            if (updated == null || updated != 1) {
                throw stateConflict("Model Turn failure lease is no longer current.");
            }
        }
        catch (CodingModelTurnAccessException failure) {
            throw failure;
        }
        catch (DataAccessException failure) {
            throw transientFailure();
        }
    }

    private CodingModelTurnPermit reserveInTransaction(
            byte[] credentialDigest,
            byte[] requestDigest,
            CodingModelTurnContract.Request request) {
        Instant now = clock.instant();
        OffsetDateTime databaseNow = databaseTime(now);
        List<UUID> credentialIds = jdbcTemplate.query(
                FIND_CREDENTIAL,
                (resultSet, rowNumber) -> resultSet.getObject("credential_id", UUID.class),
                credentialDigest,
                databaseNow,
                databaseNow);
        if (credentialIds.size() != 1) {
            throw new CodingModelTurnAccessException(
                    "SERVICE_AUTHENTICATION_FAILED",
                    "Service authentication failed.",
                    HttpStatus.UNAUTHORIZED);
        }
        jdbcTemplate.update(
                "UPDATE app.coding_service_credential SET last_used_at = ? WHERE credential_id = ?",
                databaseNow,
                credentialIds.get(0));

        List<IdempotencyRow> existingRows = jdbcTemplate.query(
                FIND_IDEMPOTENCY,
                (resultSet, rowNumber) -> new IdempotencyRow(
                        resultSet.getObject("turn_id", UUID.class),
                        resultSet.getBytes("request_digest"),
                        resultSet.getInt("attempt"),
                        resultSet.getInt("expected_state_version"),
                        resultSet.getString("status"),
                        resultSet.getObject("lease_id", UUID.class),
                        resultSet.getTimestamp("lease_expires_at").toInstant(),
                        resultSet.getString("response_json"),
                        resultSet.getString("failure_code"),
                        (Boolean) resultSet.getObject("retryable")),
                request.jobId(),
                request.idempotencyKey());

        if (!existingRows.isEmpty()) {
            IdempotencyRow existing = existingRows.get(0);
            requireSameRequest(existing, requestDigest, request);
            if ("COMPLETED".equals(existing.status())) {
                return CodingModelTurnPermit.replay(
                        request.jobId(),
                        request.idempotencyKey(),
                        cachedResponse(existing.responseJson(), request));
            }
            if ("IN_PROGRESS".equals(existing.status()) && existing.leaseExpiresAt().isAfter(now)) {
                throw new CodingModelTurnAccessException(
                        "IDEMPOTENCY_IN_PROGRESS",
                        "An identical Model Turn request is already in progress.",
                        HttpStatus.CONFLICT,
                        true,
                        Math.max(1L, Duration.between(now, existing.leaseExpiresAt()).toMillis()));
            }
            if ("FAILED".equals(existing.status()) && !Boolean.TRUE.equals(existing.retryable())) {
                throw storedFailure(existing.failureCode());
            }
        }

        JobRow job = requireJob(request, now);
        validateJob(job, request, now);
        UUID leaseId = UUID.randomUUID();
        Instant leaseExpiresAt = now.plus(leaseDuration);
        OffsetDateTime databaseLeaseExpiresAt = databaseTime(leaseExpiresAt);
        if (existingRows.isEmpty()) {
            jdbcTemplate.update("""
                    INSERT INTO app.coding_model_turn_idempotency (
                        job_id, idempotency_key, turn_id, request_digest, attempt,
                        expected_state_version, status, lease_id, lease_expires_at,
                        created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, 'IN_PROGRESS', ?, ?, ?, ?)
                    """,
                    request.jobId(),
                    request.idempotencyKey(),
                    request.turnId(),
                    requestDigest,
                    request.attempt(),
                    request.expectedStateVersion(),
                    leaseId,
                    databaseLeaseExpiresAt,
                    databaseNow,
                    databaseNow);
        }
        else {
            jdbcTemplate.update("""
                    UPDATE app.coding_model_turn_idempotency
                    SET status = 'IN_PROGRESS',
                        lease_id = ?,
                        lease_expires_at = ?,
                        response_json = NULL,
                        failure_code = NULL,
                        retryable = NULL,
                        updated_at = ?,
                        completed_at = NULL
                    WHERE job_id = ? AND idempotency_key = ?
                    """,
                    leaseId,
                    databaseLeaseExpiresAt,
                    databaseNow,
                    request.jobId(),
                    request.idempotencyKey());
        }
        return CodingModelTurnPermit.acquired(request.jobId(), request.idempotencyKey(), leaseId);
    }

    private JobRow requireJob(CodingModelTurnContract.Request request, Instant now) {
        List<JobRow> jobs = jdbcTemplate.query(
                FIND_JOB,
                (resultSet, rowNumber) -> new JobRow(
                        resultSet.getObject("trace_id", UUID.class),
                        resultSet.getString("authority_source"),
                        resultSet.getString("status"),
                        resultSet.getInt("state_version"),
                        resultSet.getString("context_digest"),
                        resultSet.getString("prompt_version"),
                        sqlArray(resultSet.getArray("allowed_capabilities")),
                        sqlArray(resultSet.getArray("allowed_nodes")),
                        resultSet.getTimestamp("expires_at").toInstant()),
                request.jobId());
        if (jobs.isEmpty()) {
            throw new CodingModelTurnAccessException(
                    "JOB_NOT_FOUND", "Coding Job was not found.", HttpStatus.NOT_FOUND);
        }
        return jobs.get(0);
    }

    private static void validateJob(
            JobRow job,
            CodingModelTurnContract.Request request,
            Instant now) {
        if (!job.expiresAt().isAfter(now) || "EXPIRED".equals(job.status())) {
            throw new CodingModelTurnAccessException(
                    "JOB_EXPIRED", "Coding Job has expired.", HttpStatus.NOT_FOUND);
        }
        if (!"SPRING_CONTROL_PLANE".equals(job.authoritySource())
                || !"RUNNING".equals(job.status())
                || !job.traceId().equals(request.traceId())
                || !job.contextDigest().equals(request.contextDigest())
                || !job.promptVersion().equals(request.promptVersion())
                || !job.allowedCapabilities().containsAll(request.requiredCapabilities())
                || !job.allowedNodes().contains(request.nodeName())) {
            throw new CodingModelTurnAccessException(
                    "SERVICE_AUTHORIZATION_DENIED",
                    "Service is not authorized for the requested Coding Job scope.",
                    HttpStatus.FORBIDDEN);
        }
        if (job.stateVersion() != request.expectedStateVersion()) {
            throw stateConflict("Coding Job state version does not match the request.");
        }
    }

    private static void requireSameRequest(
            IdempotencyRow existing,
            byte[] requestDigest,
            CodingModelTurnContract.Request request) {
        if (!existing.turnId().equals(request.turnId())
                || !MessageDigest.isEqual(existing.requestDigest(), requestDigest)
                || existing.attempt() != request.attempt()
                || existing.expectedStateVersion() != request.expectedStateVersion()) {
            throw new CodingModelTurnAccessException(
                    "IDEMPOTENCY_KEY_REUSED",
                    "Idempotency key was reused with a different Model Turn request.",
                    HttpStatus.CONFLICT);
        }
    }

    private CodingModelTurnContract.Response cachedResponse(
            String responseJson,
            CodingModelTurnContract.Request request) {
        try {
            CodingModelTurnContract.Response response = objectMapper.readValue(
                    responseJson, CodingModelTurnContract.Response.class);
            if (!response.turnId().equals(request.turnId())
                    || !response.jobId().equals(request.jobId())
                    || !response.traceId().equals(request.traceId())
                    || !response.idempotencyKey().equals(request.idempotencyKey())) {
                throw new JsonProcessingException("correlation mismatch") { };
            }
            return response;
        }
        catch (JsonProcessingException failure) {
            throw new CodingModelTurnAccessException(
                    "MODEL_RESPONSE_INVALID",
                    "Stored Model Turn response is invalid.",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    private static CodingModelTurnAccessException storedFailure(String code) {
        if (code == null) {
            return transientFailure();
        }
        HttpStatus status = switch (code) {
            case "CONTRACT_VALIDATION_FAILED" -> HttpStatus.BAD_REQUEST;
            case "MODEL_RESPONSE_INVALID" -> HttpStatus.UNPROCESSABLE_ENTITY;
            case "MODEL_RATE_LIMITED" -> HttpStatus.TOO_MANY_REQUESTS;
            case "MODEL_TIMEOUT" -> HttpStatus.GATEWAY_TIMEOUT;
            case "MODEL_NOT_CONFIGURED", "MODEL_CAPABILITY_UNSUPPORTED",
                    "MODEL_PROVIDER_UNAVAILABLE", "INTERNAL_TRANSIENT_ERROR" -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        return new CodingModelTurnAccessException(
                code,
                "A previous identical Model Turn request failed without a retryable outcome.",
                status);
    }

    private static byte[] credentialDigest(String authorizationHeader) {
        if (authorizationHeader == null
                || authorizationHeader.length() < 8
                || authorizationHeader.length() > 519
                || !authorizationHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new CodingModelTurnAccessException(
                    "SERVICE_AUTHENTICATION_FAILED",
                    "Service authentication failed.",
                    HttpStatus.UNAUTHORIZED);
        }
        byte[] credential = new byte[authorizationHeader.length() - 7];
        try {
            for (int index = 7; index < authorizationHeader.length(); index++) {
                char value = authorizationHeader.charAt(index);
                if (value < 0x21 || value > 0x7e) {
                    throw new CodingModelTurnAccessException(
                            "SERVICE_AUTHENTICATION_FAILED",
                            "Service authentication failed.",
                            HttpStatus.UNAUTHORIZED);
                }
                credential[index - 7] = (byte) value;
            }
            return MessageDigest.getInstance("SHA-256").digest(credential);
        }
        catch (NoSuchAlgorithmException failure) {
            throw transientFailure();
        }
        finally {
            Arrays.fill(credential, (byte) 0);
        }
    }

    private static Set<String> sqlArray(Array value) throws SQLException {
        if (value == null) {
            return Set.of();
        }
        Object array = value.getArray();
        if (array instanceof String[] strings) {
            return Set.of(strings);
        }
        Object[] values = (Object[]) array;
        return Arrays.stream(values).map(String::valueOf).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static OffsetDateTime databaseTime(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static void requireActivePermit(CodingModelTurnPermit permit) {
        Objects.requireNonNull(permit, "permit is required");
        if (permit.replay() || permit.leaseId() == null) {
            throw new IllegalArgumentException("An acquired Model Turn permit is required.");
        }
    }

    private static CodingModelTurnAccessException stateConflict(String message) {
        return new CodingModelTurnAccessException(
                "JOB_STATE_VERSION_CONFLICT", message, HttpStatus.CONFLICT);
    }

    private static CodingModelTurnAccessException transientFailure() {
        return new CodingModelTurnAccessException(
                "INTERNAL_TRANSIENT_ERROR",
                "Coding Model Turn authority is temporarily unavailable.",
                HttpStatus.SERVICE_UNAVAILABLE,
                true,
                1_000L);
    }

    private record JobRow(
            UUID traceId,
            String authoritySource,
            String status,
            int stateVersion,
            String contextDigest,
            String promptVersion,
            Set<String> allowedCapabilities,
            Set<String> allowedNodes,
            Instant expiresAt) {
    }

    private record IdempotencyRow(
            UUID turnId,
            byte[] requestDigest,
            int attempt,
            int expectedStateVersion,
            String status,
            UUID leaseId,
            Instant leaseExpiresAt,
            String responseJson,
            String failureCode,
            Boolean retryable) {
    }
}

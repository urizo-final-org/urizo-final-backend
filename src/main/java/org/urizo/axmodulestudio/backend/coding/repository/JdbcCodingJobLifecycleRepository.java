package org.urizo.axmodulestudio.backend.coding.repository;

import org.urizo.axmodulestudio.backend.coding.dto.CodingJobLifecycleContract;
import org.urizo.axmodulestudio.backend.coding.service.CodingJobLifecycleException;
import org.urizo.axmodulestudio.backend.coding.service.CodingJobStateMachine;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

public final class JdbcCodingJobLifecycleRepository implements CodingJobLifecycleRepository {

    private static final String JOB_COLUMNS = """
            job_id, trace_id, profile_version_id, actor_id, project_id, repository_id, graph_step,
            status, state_version, prompt_version, allowed_capabilities, allowed_nodes,
            expires_at, created_at, started_at, updated_at, finished_at,
            failure_code, failure_retryable
            """;

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public JdbcCodingJobLifecycleRepository(
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate,
            ObjectMapper objectMapper,
            Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public CodingJobLifecycleContract.JobResponse create(
            UUID traceId,
            String idempotencyKey,
            byte[] requestDigest,
            CodingJobLifecycleContract.CreateRequest request) {
        return Objects.requireNonNull(transactionTemplate.execute(status -> {
            lockIdempotencyKey("CREATE", idempotencyKey);
            CommandReplay replay = findReplay("CREATE", idempotencyKey);
            if (replay != null) {
                requireReplayMatch(replay, null, requestDigest);
                return decodeResponse(replay.responseJson());
            }

            ProfileState profile = requireActiveLlmOpsProfile(request.profileVersionId());
            Instant now = databaseInstant();
            UUID jobId = UUID.randomUUID();
            CodingJobLifecycleContract.JobResponse response = new CodingJobLifecycleContract.JobResponse(
                    CodingJobLifecycleContract.SCHEMA_VERSION,
                    jobId,
                    traceId,
                    request.profileVersionId(),
                    request.actorId(),
                    request.projectId(),
                    request.repositoryId(),
                    request.graphStep(),
                    CodingJobLifecycleContract.Status.PENDING,
                    1,
                    request.promptVersion(),
                    request.allowedCapabilities(),
                    request.allowedNodes(),
                    request.expiresAt().truncatedTo(ChronoUnit.MICROS),
                    now,
                    null,
                    now,
                    null,
                    null);
            insertJob(response, request, profile.workerMaxAttempts());
            insertCommand(
                    "CREATE",
                    idempotencyKey,
                    requestDigest,
                    jobId,
                    null,
                    CodingJobLifecycleContract.Status.PENDING,
                    1,
                    response,
                    now);
            return response;
        }));
    }

    @Override
    public CodingJobLifecycleContract.JobResponse find(UUID jobId, UUID traceId) {
        List<CodingJobLifecycleContract.JobResponse> rows = jdbcTemplate.query(
                "SELECT " + JOB_COLUMNS + " FROM app.coding_job "
                        + "WHERE job_id = ? AND trace_id = ? "
                        + "AND authority_source = 'SPRING_CONTROL_PLANE'",
                JdbcCodingJobLifecycleRepository::mapJob,
                jobId,
                traceId);
        if (rows.size() != 1) {
            throw notFound();
        }
        return rows.get(0);
    }

    @Override
    public CodingJobLifecycleContract.JobResponse transition(
            UUID jobId,
            UUID traceId,
            String idempotencyKey,
            byte[] requestDigest,
            CodingJobLifecycleContract.TransitionRequest request) {
        return Objects.requireNonNull(transactionTemplate.execute(status -> {
            lockIdempotencyKey("TRANSITION", idempotencyKey);
            CommandReplay replay = findReplay("TRANSITION", idempotencyKey);
            if (replay != null) {
                requireReplayMatch(replay, jobId, requestDigest);
                return decodeResponse(replay.responseJson());
            }

            CodingJobLifecycleContract.JobResponse current = findLocked(jobId, traceId);
            if (current.stateVersion() != request.expectedStateVersion()) {
                throw new CodingJobLifecycleException(
                        "JOB_STATE_VERSION_CONFLICT",
                        "expectedStateVersion does not match the authoritative coding job state.",
                        HttpStatus.CONFLICT);
            }

            Instant now = databaseInstant();
            CodingJobStateMachine.requireTransition(
                    current.status(), request.targetStatus(), now, current.expiresAt());
            Instant startedAt = current.startedAt();
            if (startedAt == null && request.targetStatus() == CodingJobLifecycleContract.Status.RUNNING) {
                startedAt = now;
            }
            Instant finishedAt = request.targetStatus().terminal() ? now : null;
            int nextVersion = current.stateVersion() + 1;
            CodingJobLifecycleContract.JobResponse response = new CodingJobLifecycleContract.JobResponse(
                    CodingJobLifecycleContract.SCHEMA_VERSION,
                    current.jobId(),
                    current.traceId(),
                    current.profileVersionId(),
                    current.actorId(),
                    current.projectId(),
                    current.repositoryId(),
                    current.graphStep(),
                    request.targetStatus(),
                    nextVersion,
                    current.promptVersion(),
                    current.allowedCapabilities(),
                    current.allowedNodes(),
                    current.expiresAt(),
                    current.createdAt(),
                    startedAt,
                    now,
                    finishedAt,
                    request.failure());
            updateJob(response);
            insertCommand(
                    "TRANSITION",
                    idempotencyKey,
                    requestDigest,
                    jobId,
                    current.status(),
                    request.targetStatus(),
                    nextVersion,
                    response,
                    now);
            return response;
        }));
    }

    private void lockIdempotencyKey(String commandType, String idempotencyKey) {
        String lockKey = commandType + ":" + idempotencyKey;
        jdbcTemplate.query(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                preparedStatement -> preparedStatement.setString(1, lockKey),
                resultSet -> null);
    }

    private CommandReplay findReplay(String commandType, String idempotencyKey) {
        List<CommandReplay> rows = jdbcTemplate.query(
                """
                SELECT job_id, request_digest, response_json::text
                FROM app.coding_job_lifecycle_command
                WHERE command_type = ? AND idempotency_key = ?
                """,
                (resultSet, rowNumber) -> new CommandReplay(
                        resultSet.getObject("job_id", UUID.class),
                        resultSet.getBytes("request_digest"),
                        resultSet.getString("response_json")),
                commandType,
                idempotencyKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static void requireReplayMatch(
            CommandReplay replay,
            UUID expectedJobId,
            byte[] requestDigest) {
        boolean jobMatches = expectedJobId == null || expectedJobId.equals(replay.jobId());
        if (!jobMatches || !MessageDigest.isEqual(replay.requestDigest(), requestDigest)) {
            throw new CodingJobLifecycleException(
                    "IDEMPOTENCY_KEY_REUSED",
                    "Idempotency-Key was already used for a different coding job command.",
                    HttpStatus.CONFLICT);
        }
    }

    private CodingJobLifecycleContract.JobResponse findLocked(UUID jobId, UUID traceId) {
        List<CodingJobLifecycleContract.JobResponse> rows = jdbcTemplate.query(
                "SELECT " + JOB_COLUMNS + " FROM app.coding_job "
                        + "WHERE job_id = ? AND trace_id = ? "
                        + "AND authority_source = 'SPRING_CONTROL_PLANE' FOR UPDATE",
                JdbcCodingJobLifecycleRepository::mapJob,
                jobId,
                traceId);
        if (rows.size() != 1) {
            throw notFound();
        }
        return rows.get(0);
    }

    private void insertJob(
            CodingJobLifecycleContract.JobResponse response,
            CodingJobLifecycleContract.CreateRequest request,
            int workerMaxAttempts) {
        int updated = jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO app.coding_job (
                        job_id, trace_id, status, state_version, context_digest,
                        prompt_version, allowed_capabilities, allowed_nodes, expires_at,
                        created_at, updated_at, authority_source, actor_id, project_id,
                        repository_id, graph_step, base_sha, policy_hash, profile_version_id,
                        worker_max_attempts
                    ) VALUES (?, ?, 'PENDING', 1, ?, ?, ?, ?, ?, ?, ?,
                              'SPRING_CONTROL_PLANE', ?, ?, ?, ?, ?, ?, ?, ?)
                    """);
            int index = 1;
            statement.setObject(index++, response.jobId());
            statement.setObject(index++, response.traceId());
            statement.setString(index++, request.contextDigest());
            statement.setString(index++, response.promptVersion());
            statement.setArray(index++, connection.createArrayOf(
                    "varchar", response.allowedCapabilities().toArray(String[]::new)));
            statement.setArray(index++, connection.createArrayOf(
                    "varchar", response.allowedNodes().toArray(String[]::new)));
            statement.setTimestamp(index++, Timestamp.from(response.expiresAt()));
            statement.setTimestamp(index++, Timestamp.from(response.createdAt()));
            statement.setTimestamp(index++, Timestamp.from(response.updatedAt()));
            statement.setObject(index++, response.actorId());
            statement.setObject(index++, response.projectId());
            statement.setObject(index++, response.repositoryId());
            statement.setString(index++, response.graphStep());
            statement.setString(index++, request.baseSha());
            statement.setString(index++, request.policyHash());
            statement.setObject(index++, response.profileVersionId());
            statement.setInt(index, workerMaxAttempts);
            return statement;
        });
        if (updated != 1) {
            throw new IllegalStateException("Coding job creation did not affect exactly one row.");
        }
    }

    private void updateJob(CodingJobLifecycleContract.JobResponse response) {
        String failureCode = response.failure() == null ? null : response.failure().code();
        Boolean failureRetryable = response.failure() == null ? null : response.failure().retryable();
        int updated = jdbcTemplate.update("""
                UPDATE app.coding_job
                SET status = ?, state_version = ?, started_at = ?, finished_at = ?,
                    failure_code = ?, failure_retryable = ?, updated_at = ?
                WHERE job_id = ? AND trace_id = ? AND authority_source = 'SPRING_CONTROL_PLANE'
                """,
                response.status().name(),
                response.stateVersion(),
                timestamp(response.startedAt()),
                timestamp(response.finishedAt()),
                failureCode,
                failureRetryable,
                Timestamp.from(response.updatedAt()),
                response.jobId(),
                response.traceId());
        if (updated != 1) {
            throw new IllegalStateException("Coding job transition did not affect exactly one row.");
        }
    }

    private void insertCommand(
            String commandType,
            String idempotencyKey,
            byte[] requestDigest,
            UUID jobId,
            CodingJobLifecycleContract.Status fromStatus,
            CodingJobLifecycleContract.Status toStatus,
            int resultStateVersion,
            CodingJobLifecycleContract.JobResponse response,
            Instant now) {
        int updated = jdbcTemplate.update("""
                INSERT INTO app.coding_job_lifecycle_command (
                    command_id, command_type, idempotency_key, request_digest, job_id,
                    from_status, to_status, result_state_version, response_json, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?)
                """,
                UUID.randomUUID(),
                commandType,
                idempotencyKey,
                requestDigest,
                jobId,
                fromStatus == null ? null : fromStatus.name(),
                toStatus.name(),
                resultStateVersion,
                encodeResponse(response),
                Timestamp.from(now));
        if (updated != 1) {
            throw new IllegalStateException("Coding job command audit was not persisted.");
        }
    }

    private String encodeResponse(CodingJobLifecycleContract.JobResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        }
        catch (JsonProcessingException failure) {
            throw new IllegalStateException("Coding job response could not be persisted.", failure);
        }
    }

    private CodingJobLifecycleContract.JobResponse decodeResponse(String responseJson) {
        byte[] encoded = responseJson.getBytes(StandardCharsets.UTF_8);
        try {
            return objectMapper.readValue(encoded, CodingJobLifecycleContract.JobResponse.class);
        }
        catch (IOException failure) {
            throw new IllegalStateException("Persisted coding job response is invalid.", failure);
        }
        finally {
            Arrays.fill(encoded, (byte) 0);
        }
    }

    private static CodingJobLifecycleContract.JobResponse mapJob(
            ResultSet resultSet,
            int rowNumber) throws SQLException {
        String failureCode = resultSet.getString("failure_code");
        Boolean failureRetryable = resultSet.getObject("failure_retryable", Boolean.class);
        CodingJobLifecycleContract.Failure failure = failureCode == null
                ? null
                : new CodingJobLifecycleContract.Failure(failureCode, Boolean.TRUE.equals(failureRetryable));
        return new CodingJobLifecycleContract.JobResponse(
                CodingJobLifecycleContract.SCHEMA_VERSION,
                resultSet.getObject("job_id", UUID.class),
                resultSet.getObject("trace_id", UUID.class),
                resultSet.getObject("profile_version_id", UUID.class),
                resultSet.getObject("actor_id", UUID.class),
                resultSet.getObject("project_id", UUID.class),
                resultSet.getObject("repository_id", UUID.class),
                resultSet.getString("graph_step"),
                CodingJobLifecycleContract.Status.valueOf(resultSet.getString("status")),
                resultSet.getInt("state_version"),
                resultSet.getString("prompt_version"),
                strings(resultSet.getArray("allowed_capabilities")),
                strings(resultSet.getArray("allowed_nodes")),
                instant(resultSet, "expires_at"),
                instant(resultSet, "created_at"),
                instant(resultSet, "started_at"),
                instant(resultSet, "updated_at"),
                instant(resultSet, "finished_at"),
                failure);
    }

    private ProfileState requireActiveLlmOpsProfile(UUID profileVersionId) {
        List<ProfileState> rows = jdbcTemplate.query(
                "SELECT profile_key, status, "
                        + "(snapshot_json #>> '{config,maxAttempts}')::integer "
                        + "AS worker_max_attempts "
                        + "FROM app.ai_profile_version "
                        + "WHERE profile_version_id = ?",
                (resultSet, rowNumber) -> new ProfileState(
                        resultSet.getString("profile_key"),
                        resultSet.getString("status"),
                        resultSet.getObject("worker_max_attempts", Integer.class)),
                profileVersionId);
        if (rows.isEmpty()) {
            throw new CodingJobLifecycleException(
                    "PROFILE_VERSION_NOT_FOUND",
                    "The requested AI Profile Version was not found.",
                    HttpStatus.NOT_FOUND);
        }
        ProfileState profile = rows.get(0);
        if (!"LLM_OPS".equals(profile.profileKey()) || !"ACTIVE".equals(profile.status())) {
            throw new CodingJobLifecycleException(
                    "PROFILE_VERSION_NOT_ACTIVE",
                    "An ACTIVE LLM_OPS Profile Version is required.",
                    HttpStatus.CONFLICT);
        }
        if (profile.workerMaxAttempts() == null
                || profile.workerMaxAttempts() < 1
                || profile.workerMaxAttempts() > 20) {
            throw new CodingJobLifecycleException(
                    "PROFILE_VERSION_INVALID",
                    "The active LLM_OPS Profile Version has an invalid maxAttempts value.",
                    HttpStatus.CONFLICT);
        }
        return profile;
    }

    private static List<String> strings(Array sqlArray) throws SQLException {
        Object[] values = (Object[]) sqlArray.getArray();
        return Arrays.stream(values).map(String::valueOf).toList();
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        Timestamp value = resultSet.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private Instant databaseInstant() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

    private static CodingJobLifecycleException notFound() {
        return new CodingJobLifecycleException(
                "JOB_NOT_FOUND",
                "The authoritative coding job was not found in the supplied trace scope.",
                HttpStatus.NOT_FOUND);
    }

    private record CommandReplay(UUID jobId, byte[] requestDigest, String responseJson) {
    }

    private record ProfileState(String profileKey, String status, Integer workerMaxAttempts) {
    }
}

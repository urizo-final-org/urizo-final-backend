package org.urizo.axmodulestudio.backend.coding.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.urizo.axmodulestudio.backend.coding.dto.CodingRunnerContract;

@Service
@ConditionalOnProperty(prefix = "ax.coding.model-turn-bridge", name = "enabled", havingValue = "true")
public class CodingRunnerService {

    private static final Duration DEFAULT_LEASE = Duration.ofMinutes(2);

    private static final Map<String, Duration> LEASE_BY_KIND = Map.of(
            "CREATE_WORKTREE", Duration.ofMinutes(2),
            "PREPARE_SCAN_WORKTREE", Duration.ofMinutes(2),
            "BUILD", Duration.ofMinutes(15),
            "TEST", Duration.ofMinutes(15),
            "PREVIEW_UP", Duration.ofMinutes(10),
            "PREVIEW_DOWN", Duration.ofMinutes(10),
            "CREATE_PR", Duration.ofMinutes(3),
            "CHECK_DEV_MERGE", Duration.ofMinutes(3),
            "DEPLOY_LOCAL_COMPOSE", Duration.ofMinutes(20));

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    CodingRunnerService(
            @Qualifier("codingModelTurnJdbcTemplate") JdbcTemplate jdbc,
            @Qualifier("codingModelTurnTransactionTemplate") TransactionTemplate transactions,
            ObjectMapper objectMapper,
            Clock clock) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** Returns null when no task is waiting. The controller answers 204 in that case. */
    public CodingRunnerContract.ClaimResponse claim(
            String authorization, CodingRunnerContract.ClaimRequest request) {
        return authenticated(authorization, () -> {
            Instant now = Instant.now(clock);
            reapExpiredLeases(now);
            List<TaskRow> waiting = jdbc.query(
                    "SELECT task_id, kind, payload::text, attempt, max_attempts "
                            + "FROM app.coding_runner_task WHERE status = 'PENDING' "
                            + "ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1",
                    (rs, row) -> new TaskRow(rs.getObject(1, UUID.class), rs.getString(2),
                            rs.getString(3), rs.getInt(4), rs.getInt(5)));
            if (waiting.isEmpty()) {
                return null;
            }
            TaskRow task = waiting.get(0);
            UUID leaseId = UUID.randomUUID();
            Instant leaseExpiresAt = now.plus(
                    LEASE_BY_KIND.getOrDefault(task.kind(), DEFAULT_LEASE));
            int updated = jdbc.update("UPDATE app.coding_runner_task SET status = 'RUNNING', "
                            + "lease_id = ?, lease_expires_at = ?, last_heartbeat_at = ?, "
                            + "started_at = COALESCE(started_at, ?), attempt = attempt + 1, "
                            + "updated_at = ? WHERE task_id = ? AND status = 'PENDING'",
                    leaseId, Timestamp.from(leaseExpiresAt), Timestamp.from(now),
                    Timestamp.from(now), Timestamp.from(now), task.taskId());
            if (updated != 1) {
                throw unavailable();
            }
            return new CodingRunnerContract.ClaimResponse(
                    CodingRunnerContract.SCHEMA_VERSION, request.traceId(), task.taskId(),
                    task.kind(), readJson(task.payload()), leaseId, leaseExpiresAt,
                    task.attempt() + 1, task.maxAttempts());
        });
    }

    /**
     * Adds one runner command to the queue. The runner claims a single PENDING
     * row ordered by creation time, so commands queued together run in the order
     * they are added here. The kind is bounded by the table check constraint and
     * the runner's own allowlist; the payload never names a build target.
     */
    public UUID enqueue(String kind, JsonNode payload) {
        return enqueue(UUID.randomUUID(), kind, payload);
    }

    /** Enqueues a replay-safe external operation under a caller-derived stable task id. */
    public UUID enqueue(UUID taskId, String kind, JsonNode payload) {
        Objects.requireNonNull(taskId, "taskId is required");
        Objects.requireNonNull(kind, "kind is required");
        Objects.requireNonNull(payload, "payload is required");
        if (!LEASE_BY_KIND.containsKey(kind) || !payload.isObject()) {
            throw new IllegalArgumentException("The runner command is not registered.");
        }
        String encoded;
        try {
            encoded = objectMapper.writeValueAsString(payload);
        }
        catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("The runner payload is invalid.", failure);
        }
        int inserted = jdbc.update("INSERT INTO app.coding_runner_task (task_id, kind, payload) "
                + "VALUES (?, ?, ?::jsonb) ON CONFLICT (task_id) DO NOTHING",
                taskId, kind, encoded);
        if (inserted == 0) {
            List<TaskBinding> bindings = jdbc.query(
                    "SELECT kind, payload::text FROM app.coding_runner_task WHERE task_id = ?",
                    (rs, row) -> new TaskBinding(rs.getString(1), readJson(rs.getString(2))),
                    taskId);
            if (bindings.size() != 1
                    || !kind.equals(bindings.get(0).kind())
                    || !payload.equals(bindings.get(0).payload())) {
                throw new CodingWorkerException(
                        "RUNNER_TASK_CONFLICT",
                        "The runner task id is already bound to another command.",
                        HttpStatus.CONFLICT);
            }
        }
        return taskId;
    }

    /**
     * Reads one queued command back. A caller that queued work has no other way to learn what
     * happened, because the runner reports to this service rather than to whoever asked.
     *
     * <p>A task that is still {@code PENDING} is the normal state while no runner is running; that
     * is a wait, not a failure.
     */
    public TaskOutcome taskOutcome(UUID taskId, String expectedKind) {
        Objects.requireNonNull(taskId, "taskId is required");
        List<TaskOutcome> found = jdbc.query(
                "SELECT status, error_code, result_json::text FROM app.coding_runner_task "
                        + "WHERE task_id = ? AND kind = ?",
                (row, index) -> {
                    // result_json stays null until the runner reports, which is the normal state
                    // while the command is still queued.
                    String encoded = row.getString("result_json");
                    return new TaskOutcome(
                            row.getString("status"),
                            row.getString("error_code"),
                            encoded == null ? null : readJson(encoded));
                },
                taskId, expectedKind);
        if (found.isEmpty()) {
            throw new CodingWorkerException(
                    "RUNNER_TASK_NOT_FOUND",
                    "The runner command was not found.",
                    HttpStatus.NOT_FOUND);
        }
        return found.get(0);
    }

    /** What the runner reported for one queued command. {@code result} is null until it finishes. */
    public record TaskOutcome(String status, String errorCode, JsonNode result) { }

    public CodingRunnerContract.HeartbeatResponse heartbeat(
            String authorization, CodingRunnerContract.HeartbeatRequest request) {
        return authenticated(authorization, () -> {
            Instant now = Instant.now(clock);
            String kind = requireLeasedKind(request.taskId(), request.leaseId(), now);
            Instant leaseExpiresAt = now.plus(LEASE_BY_KIND.getOrDefault(kind, DEFAULT_LEASE));
            int updated = jdbc.update("UPDATE app.coding_runner_task SET lease_expires_at = ?, "
                            + "last_heartbeat_at = ?, updated_at = ? "
                            + "WHERE task_id = ? AND lease_id = ? AND status = 'RUNNING'",
                    Timestamp.from(leaseExpiresAt), Timestamp.from(now), Timestamp.from(now),
                    request.taskId(), request.leaseId());
            if (updated != 1) {
                throw leaseConflict();
            }
            return new CodingRunnerContract.HeartbeatResponse(
                    CodingRunnerContract.SCHEMA_VERSION, request.traceId(), request.taskId(),
                    request.leaseId(), leaseExpiresAt);
        });
    }

    public CodingRunnerContract.OutcomeResponse outcome(
            String authorization, CodingRunnerContract.OutcomeRequest request) {
        return authenticated(authorization, () -> {
            Instant now = Instant.now(clock);
            requireLeasedKind(request.taskId(), request.leaseId(), now);
            TaskCounters counters = counters(request.taskId());
            String status = resolveStatus(request.outcome(), counters);
            boolean finished = !"PENDING".equals(status);
            int updated = jdbc.update("UPDATE app.coding_runner_task SET status = ?, "
                            + "lease_id = NULL, lease_expires_at = NULL, last_heartbeat_at = ?, "
                            + "result_json = COALESCE(?::jsonb, result_json), "
                            + "error_code = COALESCE(?, error_code), "
                            + "finished_at = CASE WHEN CAST(? AS boolean) "
                            + "THEN CAST(? AS timestamptz) ELSE NULL END, updated_at = ? "
                            + "WHERE task_id = ? AND lease_id = ? AND status = 'RUNNING'",
                    status, Timestamp.from(now), writeJson(request.result()),
                    request.errorCode(), finished, Timestamp.from(now), Timestamp.from(now),
                    request.taskId(), request.leaseId());
            if (updated != 1) {
                throw leaseConflict();
            }
            return new CodingRunnerContract.OutcomeResponse(
                    CodingRunnerContract.SCHEMA_VERSION, request.traceId(), request.taskId(),
                    status, counters.attempt());
        });
    }

    /**
     * A runner that dies keeps its task in RUNNING forever, so an expired lease is released here.
     * The task returns to PENDING until it runs out of attempts, then it fails loudly.
     */
    private void reapExpiredLeases(Instant now) {
        jdbc.update("UPDATE app.coding_runner_task SET "
                        + "status = CASE WHEN attempt >= max_attempts THEN 'FAILED' ELSE 'PENDING' END, "
                        + "lease_id = NULL, lease_expires_at = NULL, "
                        + "error_code = CASE WHEN attempt >= max_attempts "
                        + "THEN 'RUNNER_LEASE_EXPIRED' ELSE error_code END, "
                        + "finished_at = CASE WHEN attempt >= max_attempts "
                        + "THEN CAST(? AS timestamptz) ELSE NULL END, "
                        + "updated_at = ? WHERE status = 'RUNNING' AND lease_expires_at <= ?",
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
    }

    private String resolveStatus(String outcome, TaskCounters counters) {
        return switch (outcome) {
            case "SUCCEEDED" -> "SUCCEEDED";
            case "PERMANENT_FAILURE" -> "FAILED";
            default -> counters.attempt() >= counters.maxAttempts() ? "FAILED" : "PENDING";
        };
    }

    private String requireLeasedKind(UUID taskId, UUID leaseId, Instant now) {
        List<String> rows = jdbc.query(
                "SELECT kind FROM app.coding_runner_task WHERE task_id = ? AND lease_id = ? "
                        + "AND status = 'RUNNING' AND lease_expires_at > ? FOR UPDATE",
                (rs, row) -> rs.getString(1), taskId, leaseId, Timestamp.from(now));
        if (rows.size() != 1) {
            throw leaseConflict();
        }
        return rows.get(0);
    }

    private TaskCounters counters(UUID taskId) {
        List<TaskCounters> rows = jdbc.query(
                "SELECT attempt, max_attempts FROM app.coding_runner_task WHERE task_id = ?",
                (rs, row) -> new TaskCounters(rs.getInt(1), rs.getInt(2)), taskId);
        if (rows.size() != 1) {
            throw leaseConflict();
        }
        return rows.get(0);
    }

    private <T> T authenticated(String authorization, java.util.function.Supplier<T> action) {
        byte[] credentialDigest = credentialDigest(authorization);
        try {
            return transactions.execute(status -> {
                authenticate(credentialDigest);
                return action.get();
            });
        }
        finally {
            Arrays.fill(credentialDigest, (byte) 0);
        }
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

    private byte[] credentialDigest(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")
                || authorization.length() <= 7) {
            throw new CodingWorkerException(
                    "SERVICE_AUTHENTICATION_FAILED", "Service authentication failed.",
                    HttpStatus.UNAUTHORIZED);
        }
        byte[] token = authorization.substring(7).getBytes(StandardCharsets.UTF_8);
        try {
            return MessageDigest.getInstance("SHA-256").digest(token);
        }
        catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable.", failure);
        }
        finally {
            Arrays.fill(token, (byte) 0);
        }
    }

    private JsonNode readJson(String json) {
        try {
            return objectMapper.readTree(json);
        }
        catch (JsonProcessingException failure) {
            throw unavailable();
        }
    }

    private String writeJson(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return node.toString();
    }

    private static CodingWorkerException leaseConflict() {
        return new CodingWorkerException(
                "RUNNER_LEASE_CONFLICT",
                "The runner lease is no longer current for this task.",
                HttpStatus.CONFLICT);
    }

    private static CodingWorkerException unavailable() {
        return new CodingWorkerException(
                "INTERNAL_TRANSIENT_ERROR",
                "The authoritative runner task store is unavailable.",
                HttpStatus.SERVICE_UNAVAILABLE, true, 1_000L);
    }

    private record TaskRow(UUID taskId, String kind, String payload, int attempt, int maxAttempts) { }

    private record TaskBinding(String kind, JsonNode payload) { }

    private record TaskCounters(int attempt, int maxAttempts) { }
}

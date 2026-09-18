package org.urizo.axmodulestudio.backend.orchestration.monitoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Read-only local evidence. No Langfuse requests and no inferred provider usage. */
@Service
@ConditionalOnProperty(prefix = "ax.coding.model-turn-bridge", name = "enabled", havingValue = "true")
public class InputOptimizationHistory {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;

    public InputOptimizationHistory(@Qualifier("codingModelTurnJdbcTemplate") JdbcTemplate jdbc,
            ObjectMapper json, Clock clock) {
        this.jdbc = jdbc; this.json = json; this.clock = clock;
    }

    private static final String JOB_SQL = """
            SELECT j.job_id, j.profile_version_id, p.profile_version, j.status,
                   COALESCE(m.current_node_id,
                       (SELECT h.handler_key FROM app.coding_handler_result h WHERE h.job_id = j.job_id
                        ORDER BY h.recorded_at DESC, h.result_id DESC LIMIT 1), j.graph_step) AS graph_step,
                   r.request_text, j.repository_id, j.base_sha, j.created_at, j.finished_at,
                   p.snapshot_json::text,
                   c.calls, c.input_tokens, c.output_tokens, c.cached_input_tokens,
                   c.input_known, c.output_known, c.cache_known, c.cache_hits,
                   (SELECT result_port FROM app.coding_handler_result h WHERE h.job_id = j.job_id
                    AND h.handler_key = 'coding.review' ORDER BY recorded_at DESC LIMIT 1) AS review_result
            FROM app.coding_job j
            JOIN app.ai_profile_version p ON p.profile_version_id = j.profile_version_id
            LEFT JOIN app.coding_job_request r ON r.job_id = j.job_id
            LEFT JOIN app.ai_job_monitoring_state m ON m.job_id = j.job_id
                AND m.profile_version_id = j.profile_version_id
            LEFT JOIN LATERAL (
                SELECT count(*) AS calls, sum(input_tokens) AS input_tokens, sum(output_tokens) AS output_tokens,
                       sum(cached_input_tokens) AS cached_input_tokens, count(input_tokens) AS input_known,
                       count(output_tokens) AS output_known, count(cached_input_tokens) AS cache_known,
                       count(*) FILTER (WHERE cached_input_tokens > 0) AS cache_hits
                FROM app.ai_job_model_call c WHERE c.job_id = j.job_id
            ) c ON TRUE
            WHERE p.profile_key = 'LLM_OPS'
            """;

    public History list(Instant from, Instant to, UUID jobId) {
        if (from == null || to == null || !from.isBefore(to)
                || java.time.Duration.between(from, to).compareTo(java.time.Duration.ofDays(31)) > 0) throw new IllegalArgumentException("Invalid range");
        String filter = jobId == null ? "" : " AND j.job_id = ?";
        Object[] args = jobId == null ? new Object[] {Timestamp.from(from), Timestamp.from(to)}
                : new Object[] {Timestamp.from(from), Timestamp.from(to), jobId};
        var jobs = jdbc.query(JOB_SQL + " AND j.created_at >= ? AND j.created_at < ?" + filter
                + " ORDER BY j.created_at DESC, j.job_id DESC LIMIT 51", this::job, args);
        return new History(clock.instant(), jobs.subList(0, Math.min(jobs.size(), 50)), jobs.size() > 50);
    }

    public Detail detail(UUID jobId) {
        var jobs = jdbc.query(JOB_SQL + " AND j.job_id = ?", this::job, jobId);
        if (jobs.isEmpty()) throw new AiJobMonitoringException("JOB_NOT_FOUND", "Job not found", HttpStatus.NOT_FOUND);
        var calls = jdbc.query("""
                SELECT c.*, n.observation_trace_id FROM app.ai_job_model_call c
                LEFT JOIN app.ai_job_node_occurrence n ON n.job_id = c.job_id
                  AND n.profile_version_id = c.profile_version_id AND n.pipeline_attempt = c.pipeline_attempt
                  AND n.execution_attempt = c.execution_attempt AND n.node_id = c.node_id AND n.node_sequence = c.node_sequence
                WHERE c.job_id = ? ORDER BY c.call_order ASC LIMIT 501
                """, (rs, row) -> new Call(rs.getObject("call_id", UUID.class), rs.getLong("call_order"),
                        rs.getString("node_id"), rs.getObject("turn_id", UUID.class), rs.getInt("pipeline_attempt"),
                        rs.getInt("execution_attempt"), rs.getInt("provider_attempt"), rs.getString("provider"),
                        rs.getString("model"), rs.getString("status"), rs.getString("error_code"),
                        rs.getTimestamp("started_at").toInstant(), instant(rs, "finished_at"),
                        nullableLong(rs, "input_tokens"), nullableLong(rs, "output_tokens"), nullableLong(rs, "cached_input_tokens"),
                        parse(rs.getString("input_processing")), rs.getString("observation_trace_id")), jobId);
        return new Detail(clock.instant(), jobs.get(0), calls.subList(0, Math.min(calls.size(), 500)), calls.size() > 500);
    }

    private Job job(ResultSet rs, int row) throws SQLException {
        JsonNode snapshot = parse(rs.getString("snapshot_json"));
        var settings = json.createObjectNode();
        if (snapshot != null) {
            settings.set("nodes", snapshot.path("nodes"));
            settings.set("modelBindings", snapshot.path("modelBindings"));
            settings.set("toolBindings", snapshot.path("toolBindings"));
        }
        // Snapshot authority is already exposed to the same SUPER_ADMIN; never include credentials or tool bodies.
        return new Job(rs.getObject("job_id", UUID.class), rs.getObject("profile_version_id", UUID.class),
                rs.getInt("profile_version"), "LLM_OPS", rs.getString("status"), rs.getString("graph_step"),
                rs.getString("request_text"), rs.getObject("repository_id", UUID.class), rs.getString("base_sha"),
                rs.getTimestamp("created_at").toInstant(), instant(rs, "finished_at"), settings,
                rs.getLong("calls"), nullableLong(rs, "input_tokens"), nullableLong(rs, "output_tokens"),
                nullableLong(rs, "cached_input_tokens"), rs.getLong("input_known"), rs.getLong("output_known"),
                rs.getLong("cache_known"), rs.getLong("cache_hits"), rs.getString("review_result"));
    }

    private JsonNode parse(String value) {
        if (value == null) return null;
        try { return json.readTree(value); }
        catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { return null; }
    }
    private static Instant instant(ResultSet rs, String key) throws SQLException {
        var value = rs.getTimestamp(key); return value == null ? null : value.toInstant();
    }
    private static Long nullableLong(ResultSet rs, String key) throws SQLException {
        long value = rs.getLong(key); return rs.wasNull() ? null : value;
    }

    public record History(Instant observedAt, List<Job> jobs, boolean truncated) { }
    public record Detail(Instant observedAt, Job job, List<Call> calls, boolean truncated) { }
    public record Job(UUID jobId, UUID profileVersionId, int profileVersion, String profileKey,
            String status, String stage, String request, UUID repositoryId, String baseSha,
            Instant createdAt, Instant finishedAt, JsonNode settings, long calls, Long inputTokens,
            Long outputTokens, Long cachedInputTokens, long inputKnown, long outputKnown,
            long cacheKnown, long cacheHits, String reviewResult) { }
    public record Call(UUID callId, long callOrder, String nodeId, UUID turnId, int pipelineAttempt,
            int executionAttempt, int providerAttempt, String provider, String model, String status, String errorCode,
            Instant startedAt, Instant finishedAt, Long inputTokens, Long outputTokens, Long cachedInputTokens,
            JsonNode inputProcessing, String observationTraceId) { }
}

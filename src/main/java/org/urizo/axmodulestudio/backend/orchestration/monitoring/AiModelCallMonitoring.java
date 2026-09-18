package org.urizo.axmodulestudio.backend.orchestration.monitoring;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ModelGatewayErrorCode;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ModelProvider;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderCallObserver;
import org.urizo.axmodulestudio.backend.integration.ai.observability.ModelObservationScope;
import org.urizo.axmodulestudio.backend.integration.ai.observability.InputOptimizationScope;
import org.urizo.axmodulestudio.backend.integration.ai.observability.ProviderTokenUsage;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Actual gateway attempts, independent of remote telemetry and domain state transitions. */
@Service
@ConditionalOnProperty(prefix = "ax.coding.model-turn-bridge", name = "enabled", havingValue = "true")
public final class AiModelCallMonitoring implements ProviderCallObserver {
    private static final int MAX_CALLS = 500;
    private static final ObjectMapper JSON = new ObjectMapper();
    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Autowired
    public AiModelCallMonitoring(@Qualifier("codingModelTurnDataSource") DataSource dataSource, Clock clock) {
        this(new JdbcTemplate(dataSource), clock);
        this.jdbc.setQueryTimeout(1);
    }

    public AiModelCallMonitoring(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public UUID started(ModelProvider provider, String model, int attempt) {
        var context = ModelObservationScope.current();
        if (context == null || context.jobId() == null || context.profileVersionId() == null
                || context.nodeId() == null || context.traceId() == null || context.turnId() == null
                || context.executionAttempt() == null) return null;
        UUID id = UUID.randomUUID();
        // Bind once to the exact current occurrence, never to a later retry or node.
        int inserted = jdbc.update("""
                INSERT INTO app.ai_job_model_call
                    (call_id, job_id, profile_version_id, pipeline_attempt, execution_attempt,
                     node_id, node_sequence, turn_id, provider, model, provider_attempt, status, started_at, input_processing)
                SELECT ?, n.job_id, n.profile_version_id, n.pipeline_attempt, n.execution_attempt,
                       n.node_id, n.node_sequence, ?, ?, ?, ?, 'RUNNING', ?, CAST(? AS jsonb)
                FROM app.ai_job_monitoring_state s
                JOIN app.ai_job_node_occurrence n
                  ON n.job_id = s.job_id AND n.profile_version_id = s.profile_version_id
                 AND n.pipeline_attempt = s.pipeline_attempt AND n.execution_attempt = s.execution_attempt
                 AND n.node_id = s.current_node_id AND n.node_sequence = s.current_node_sequence
                WHERE n.job_id = ? AND n.profile_version_id = ? AND n.node_id = ?
                  AND n.trace_id = ? AND n.execution_attempt = ?
                  AND n.status = 'RUNNING' AND s.current_node_status = 'RUNNING'
                """, id, context.turnId(), provider.name(), model, attempt, Timestamp.from(clock.instant()),
                processingJson(), context.jobId(), context.profileVersionId(), context.nodeId(), context.traceId(),
                context.executionAttempt());
        return inserted == 1 ? id : null;
    }

    private static String processingJson() {
        var snapshot = InputOptimizationScope.current();
        return snapshot == null ? null : JSON.valueToTree(snapshot).toString();
    }

    @Override public void usage(UUID callId, ProviderTokenUsage usage) {
        jdbc.update("""
                UPDATE app.ai_job_model_call SET input_tokens = ?, output_tokens = ?, cached_input_tokens = ?
                WHERE call_id = ? AND status = 'RUNNING'
                """, usage.input(), usage.output(), usage.cachedInput(), callId);
    }

    @Override
    public void finished(UUID callId, ModelGatewayErrorCode errorCode) {
        jdbc.update("""
                UPDATE app.ai_job_model_call SET status = ?, error_code = ?, finished_at = ?
                WHERE call_id = ? AND status = 'RUNNING'
                """, errorCode == null ? "SUCCEEDED" : "FAILED",
                errorCode == null ? null : errorCode.name(), Timestamp.from(clock.instant()), callId);
    }

    public Snapshot snapshot(UUID jobId, UUID profileVersionId) {
        try {
            List<Call> rows = jdbc.query("""
                    SELECT call_id, call_order, pipeline_attempt, execution_attempt, node_id, node_sequence,
                           turn_id, provider, model, provider_attempt, status, error_code, started_at, finished_at
                    FROM app.ai_job_model_call WHERE job_id = ? AND profile_version_id = ?
                    ORDER BY call_order DESC LIMIT ?
                    """, (rs, row) -> new Call(rs.getObject(1, UUID.class), rs.getLong(2), rs.getInt(3),
                    rs.getInt(4), rs.getString(5), rs.getLong(6), rs.getObject(7, UUID.class), rs.getString(8),
                    rs.getString(9), rs.getInt(10), rs.getString(11), rs.getString(12),
                    rs.getTimestamp(13).toInstant(), rs.getTimestamp(14) == null ? null : rs.getTimestamp(14).toInstant()),
                    jobId, profileVersionId, MAX_CALLS + 1);
            boolean truncated = rows.size() > MAX_CALLS;
            rows = new ArrayList<>(rows.subList(0, Math.min(rows.size(), MAX_CALLS)));
            Collections.reverse(rows);
            return new Snapshot("AVAILABLE", List.copyOf(rows), truncated);
        } catch (RuntimeException ignored) {
            // An unavailable model log never hides authoritative node/job status.
            return Snapshot.unavailable();
        }
    }

    public record Snapshot(String status, List<Call> calls, boolean truncated) {
        public static Snapshot unavailable() { return new Snapshot("UNAVAILABLE", List.of(), false); }
    }
    public record Call(UUID callId, long callOrder, int pipelineAttempt, int executionAttempt,
            String nodeId, long nodeSequence, UUID turnId, String provider, String model,
            int providerAttempt, String status, String errorCode, Instant startedAt, Instant finishedAt) { }
}

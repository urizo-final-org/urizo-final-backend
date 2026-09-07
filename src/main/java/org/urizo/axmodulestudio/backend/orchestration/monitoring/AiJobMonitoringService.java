package org.urizo.axmodulestudio.backend.orchestration.monitoring;

import static org.urizo.axmodulestudio.backend.orchestration.monitoring.AiJobMonitoringContract.SCHEMA_VERSION;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.urizo.axmodulestudio.backend.orchestration.monitoring.AiJobMonitoringContract.CurrentNode;
import org.urizo.axmodulestudio.backend.orchestration.monitoring.AiJobMonitoringContract.JobListResponse;
import org.urizo.axmodulestudio.backend.orchestration.monitoring.AiJobMonitoringContract.JobSnapshotResponse;
import org.urizo.axmodulestudio.backend.orchestration.monitoring.AiJobMonitoringContract.JobSummary;
import org.urizo.axmodulestudio.backend.orchestration.monitoring.AiJobMonitoringContract.LatestNodeState;
import org.urizo.axmodulestudio.backend.orchestration.monitoring.AiJobMonitoringContract.NodeDisplayStatus;
import org.urizo.axmodulestudio.backend.orchestration.monitoring.AiJobMonitoringContract.NodeOccurrence;
import org.urizo.axmodulestudio.backend.orchestration.monitoring.AiJobMonitoringContract.NodeOccurrenceReport;
import org.urizo.axmodulestudio.backend.orchestration.monitoring.AiJobMonitoringContract.NodeStatus;
import org.urizo.axmodulestudio.backend.orchestration.monitoring.AiJobMonitoringContract.ReportResponse;

@Service
@ConditionalOnProperty(prefix = "ax.coding.model-turn-bridge", name = "enabled",
        havingValue = "true")
public final class AiJobMonitoringService {

    private static final int DEFAULT_JOB_LIMIT = 50;
    private static final int MAX_JOB_LIMIT = 100;
    private static final int DEFAULT_OCCURRENCE_LIMIT = 200;
    private static final int MAX_OCCURRENCE_LIMIT = 500;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public AiJobMonitoringService(
            @Qualifier("codingModelTurnJdbcTemplate") JdbcTemplate jdbc,
            @Qualifier("codingModelTurnTransactionTemplate") TransactionTemplate transactions,
            Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc is required");
        this.transactions = Objects.requireNonNull(transactions, "transactions are required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    public ReportResponse report(String authorization, NodeOccurrenceReport report) {
        byte[] credentialDigest = credentialDigest(authorization);
        try {
            return Objects.requireNonNull(transactions.execute(status -> {
                authenticate(credentialDigest);
                requireDomainJob(report);
                OccurrenceRow occurrence = upsertOccurrence(report);
                NodeStatus monitorStatus = monitorStatus(occurrence.status(), report.nodeType());
                StateRow state = upsertState(report, occurrence, monitorStatus);
                if (occurrence.applied() && !state.applied()) {
                    state = bumpRevisionForHistory(report.jobId());
                }
                boolean current = sameOccurrence(state, occurrence);
                return new ReportResponse(
                        SCHEMA_VERSION,
                        state.monitorRevision(),
                        occurrence.applied() || state.applied(),
                        current,
                        occurrence.status(),
                        state.updatedAt());
            }));
        }
        finally {
            Arrays.fill(credentialDigest, (byte) 0);
        }
    }

    public JobListResponse list(Integer requestedLimit) {
        int limit = limit(requestedLimit, DEFAULT_JOB_LIMIT, MAX_JOB_LIMIT);
        return new JobListResponse(SCHEMA_VERSION, clock.instant(), jdbc.query(
                summarySql() + " ORDER BY domain.domain_terminal ASC, "
                        + "monitoring.updated_at DESC LIMIT ?",
                (resultSet, row) -> summary(resultSet), limit));
    }

    public JobSnapshotResponse snapshot(UUID jobId, Integer requestedLimit) {
        int limit = limit(requestedLimit, DEFAULT_OCCURRENCE_LIMIT, MAX_OCCURRENCE_LIMIT);
        List<JobSummary> summaries = jdbc.query(
                summarySql() + " WHERE monitoring.job_id = ?",
                (resultSet, row) -> summary(resultSet), jobId);
        if (summaries.size() != 1) {
            throw notFound();
        }
        List<LatestNodeState> nodes = latestNodes(jobId, summaries.get(0).profileVersionId());
        List<NodeOccurrence> descending = jdbc.query("""
                SELECT job_id, profile_version_id, pipeline_attempt, execution_attempt,
                       node_id, node_sequence, trace_id, observation_trace_id, node_type,
                       handler_key, status, started_at, waiting_at, completed_at, failed_at,
                       error_code, last_reported_at
                FROM app.ai_job_node_occurrence
                WHERE job_id = ?
                ORDER BY pipeline_attempt DESC, execution_attempt DESC, node_sequence DESC
                LIMIT ?
                """, (resultSet, row) -> occurrence(resultSet), jobId, limit + 1);
        boolean truncated = descending.size() > limit;
        if (truncated) {
            descending = new ArrayList<>(descending.subList(0, limit));
        }
        java.util.Collections.reverse(descending);
        return new JobSnapshotResponse(
                SCHEMA_VERSION, clock.instant(), summaries.get(0), nodes,
                List.copyOf(descending), truncated);
    }

    public NodeOccurrence requireOccurrence(
            UUID jobId, int pipelineAttempt, int executionAttempt, long nodeSequence) {
        List<NodeOccurrence> rows = jdbc.query("""
                SELECT job_id, profile_version_id, pipeline_attempt, execution_attempt,
                       node_id, node_sequence, trace_id, observation_trace_id, node_type,
                       handler_key, status, started_at, waiting_at, completed_at, failed_at,
                       error_code, last_reported_at
                FROM app.ai_job_node_occurrence
                WHERE job_id = ? AND pipeline_attempt = ? AND execution_attempt = ?
                  AND node_sequence = ?
                """, (resultSet, row) -> occurrence(resultSet),
                jobId, pipelineAttempt, executionAttempt, nodeSequence);
        if (rows.size() != 1) {
            throw notFound();
        }
        return rows.get(0);
    }

    private OccurrenceRow upsertOccurrence(NodeOccurrenceReport report) {
        List<OccurrenceRow> changed = jdbc.query("""
                INSERT INTO app.ai_job_node_occurrence (
                    job_id, profile_version_id, pipeline_attempt, execution_attempt,
                    node_id, node_sequence, trace_id, observation_trace_id, node_type,
                    handler_key, status, started_at, waiting_at, completed_at, failed_at,
                    error_code, last_reported_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    CASE WHEN ? = 'RUNNING' THEN ? ELSE NULL END,
                    CASE WHEN ? = 'WAITING_APPROVAL' THEN ? ELSE NULL END,
                    CASE WHEN ? = 'COMPLETED' THEN ? ELSE NULL END,
                    CASE WHEN ? = 'FAILED' THEN ? ELSE NULL END,
                    ?, ?, ?)
                ON CONFLICT (
                    job_id, profile_version_id, pipeline_attempt,
                    execution_attempt, node_id, node_sequence
                ) DO UPDATE SET
                    status = EXCLUDED.status,
                    observation_trace_id = COALESCE(
                        app.ai_job_node_occurrence.observation_trace_id,
                        EXCLUDED.observation_trace_id),
                    waiting_at = COALESCE(
                        app.ai_job_node_occurrence.waiting_at, EXCLUDED.waiting_at),
                    completed_at = COALESCE(
                        app.ai_job_node_occurrence.completed_at, EXCLUDED.completed_at),
                    failed_at = COALESCE(
                        app.ai_job_node_occurrence.failed_at, EXCLUDED.failed_at),
                    error_code = COALESCE(
                        app.ai_job_node_occurrence.error_code, EXCLUDED.error_code),
                    last_reported_at = GREATEST(
                        app.ai_job_node_occurrence.last_reported_at,
                        EXCLUDED.last_reported_at),
                    updated_at = GREATEST(
                        app.ai_job_node_occurrence.updated_at, EXCLUDED.updated_at)
                WHERE (CASE EXCLUDED.status
                        WHEN 'RUNNING' THEN 1 WHEN 'WAITING_APPROVAL' THEN 2 ELSE 3 END)
                    > (CASE app.ai_job_node_occurrence.status
                        WHEN 'RUNNING' THEN 1 WHEN 'WAITING_APPROVAL' THEN 2 ELSE 3 END)
                   OR ((CASE EXCLUDED.status
                        WHEN 'RUNNING' THEN 1 WHEN 'WAITING_APPROVAL' THEN 2 ELSE 3 END)
                       = (CASE app.ai_job_node_occurrence.status
                        WHEN 'RUNNING' THEN 1 WHEN 'WAITING_APPROVAL' THEN 2 ELSE 3 END)
                       AND EXCLUDED.status = app.ai_job_node_occurrence.status
                       AND app.ai_job_node_occurrence.observation_trace_id IS NULL
                       AND EXCLUDED.observation_trace_id IS NOT NULL)
                RETURNING status, last_reported_at
                """, (resultSet, row) -> new OccurrenceRow(
                        report.jobId(), report.profileVersionId(), report.pipelineAttempt(),
                        report.executionAttempt(), report.nodeId(), report.nodeSequence(),
                        NodeStatus.valueOf(resultSet.getString(1)),
                        resultSet.getTimestamp(2).toInstant(), true),
                report.jobId(), report.profileVersionId(), report.pipelineAttempt(),
                report.executionAttempt(), report.nodeId(), report.nodeSequence(),
                report.traceId(), report.observationTraceId(), report.nodeType(),
                report.handlerKey(), report.status().name(),
                report.status().name(), Timestamp.from(report.occurredAt()),
                report.status().name(), Timestamp.from(report.occurredAt()),
                report.status().name(), Timestamp.from(report.occurredAt()),
                report.status().name(), Timestamp.from(report.occurredAt()),
                report.errorCode(), Timestamp.from(report.occurredAt()),
                Timestamp.from(clock.instant()));
        if (!changed.isEmpty()) {
            return changed.get(0);
        }
        return jdbc.queryForObject("""
                SELECT status, last_reported_at
                FROM app.ai_job_node_occurrence
                WHERE job_id = ? AND profile_version_id = ? AND pipeline_attempt = ?
                  AND execution_attempt = ? AND node_id = ? AND node_sequence = ?
                """, (resultSet, row) -> new OccurrenceRow(
                        report.jobId(), report.profileVersionId(), report.pipelineAttempt(),
                        report.executionAttempt(), report.nodeId(), report.nodeSequence(),
                        NodeStatus.valueOf(resultSet.getString(1)),
                        resultSet.getTimestamp(2).toInstant(), false),
                report.jobId(), report.profileVersionId(), report.pipelineAttempt(),
                report.executionAttempt(), report.nodeId(), report.nodeSequence());
    }

    private StateRow upsertState(
            NodeOccurrenceReport report, OccurrenceRow occurrence, NodeStatus monitorStatus) {
        List<StateRow> changed = jdbc.query("""
                INSERT INTO app.ai_job_monitoring_state (
                    job_id, trace_id, observation_trace_id, profile_version_id,
                    pipeline_attempt, execution_attempt, monitor_status, monitor_revision,
                    current_node_id, current_node_sequence, current_node_type,
                    current_handler_key, current_node_status, last_reported_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (job_id) DO UPDATE SET
                    trace_id = EXCLUDED.trace_id,
                    observation_trace_id = COALESCE(
                        app.ai_job_monitoring_state.observation_trace_id,
                        EXCLUDED.observation_trace_id),
                    profile_version_id = EXCLUDED.profile_version_id,
                    pipeline_attempt = EXCLUDED.pipeline_attempt,
                    execution_attempt = EXCLUDED.execution_attempt,
                    monitor_status = EXCLUDED.monitor_status,
                    monitor_revision = app.ai_job_monitoring_state.monitor_revision + 1,
                    current_node_id = EXCLUDED.current_node_id,
                    current_node_sequence = EXCLUDED.current_node_sequence,
                    current_node_type = EXCLUDED.current_node_type,
                    current_handler_key = EXCLUDED.current_handler_key,
                    current_node_status = EXCLUDED.current_node_status,
                    last_reported_at = GREATEST(
                        app.ai_job_monitoring_state.last_reported_at,
                        EXCLUDED.last_reported_at),
                    updated_at = GREATEST(
                        app.ai_job_monitoring_state.updated_at, EXCLUDED.updated_at)
                WHERE (EXCLUDED.pipeline_attempt, EXCLUDED.execution_attempt,
                       EXCLUDED.current_node_sequence)
                    > (app.ai_job_monitoring_state.pipeline_attempt,
                       app.ai_job_monitoring_state.execution_attempt,
                       app.ai_job_monitoring_state.current_node_sequence)
                   OR ((EXCLUDED.pipeline_attempt, EXCLUDED.execution_attempt,
                        EXCLUDED.current_node_sequence)
                       = (app.ai_job_monitoring_state.pipeline_attempt,
                          app.ai_job_monitoring_state.execution_attempt,
                          app.ai_job_monitoring_state.current_node_sequence)
                       AND (EXCLUDED.current_node_status
                            <> app.ai_job_monitoring_state.current_node_status
                            OR (app.ai_job_monitoring_state.observation_trace_id IS NULL
                                AND EXCLUDED.observation_trace_id IS NOT NULL)))
                RETURNING monitor_revision, pipeline_attempt, execution_attempt,
                          current_node_id, current_node_sequence, updated_at
                """, (resultSet, row) -> state(resultSet, true),
                report.jobId(), report.traceId(), report.observationTraceId(),
                report.profileVersionId(), report.pipelineAttempt(), report.executionAttempt(),
                monitorStatus.name(), report.nodeId(), report.nodeSequence(), report.nodeType(),
                report.handlerKey(), occurrence.status().name(),
                Timestamp.from(occurrence.lastReportedAt()),
                Timestamp.from(clock.instant()));
        if (!changed.isEmpty()) {
            return changed.get(0);
        }
        return jdbc.queryForObject("""
                SELECT monitor_revision, pipeline_attempt, execution_attempt,
                       current_node_id, current_node_sequence, updated_at
                FROM app.ai_job_monitoring_state WHERE job_id = ?
                """, (resultSet, row) -> state(resultSet, false), report.jobId());
    }

    private StateRow bumpRevisionForHistory(UUID jobId) {
        return jdbc.queryForObject("""
                UPDATE app.ai_job_monitoring_state
                SET monitor_revision = monitor_revision + 1,
                    updated_at = GREATEST(updated_at, ?)
                WHERE job_id = ?
                RETURNING monitor_revision, pipeline_attempt, execution_attempt,
                          current_node_id, current_node_sequence, updated_at
                """, (resultSet, row) -> state(resultSet, true),
                Timestamp.from(clock.instant()), jobId);
    }

    private void authenticate(byte[] credentialDigest) {
        Instant now = clock.instant();
        List<UUID> matches = jdbc.query(
                "SELECT credential_id FROM app.coding_service_credential "
                        + "WHERE credential_digest = ? AND status IN ('ACTIVE', 'RETIRING') "
                        + "AND valid_from <= ? AND (valid_until IS NULL OR valid_until > ?) "
                        + "FOR UPDATE",
                (resultSet, row) -> resultSet.getObject(1, UUID.class),
                credentialDigest, Timestamp.from(now), Timestamp.from(now));
        if (matches.size() != 1) {
            throw new AiJobMonitoringException(
                    "SERVICE_AUTHENTICATION_FAILED", "Service authentication failed.",
                    HttpStatus.UNAUTHORIZED);
        }
        jdbc.update("UPDATE app.coding_service_credential SET last_used_at = ? "
                        + "WHERE credential_id = ?",
                Timestamp.from(now), matches.get(0));
    }

    private void requireDomainJob(NodeOccurrenceReport report) {
        List<DomainJob> jobs = jdbc.query("""
                WITH domain_job AS (
                    SELECT trace_id, profile_version_id
                    FROM app.coding_job WHERE job_id = ?
                    UNION ALL
                    SELECT trace_id, profile_version_id
                    FROM app.natural_cms_job WHERE job_id = ?
                )
                SELECT domain.trace_id, domain.profile_version_id,
                       EXISTS (
                           SELECT 1
                           FROM jsonb_array_elements(profile.snapshot_json -> 'nodes') node
                           WHERE node ->> 'id' = ? AND node ->> 'type' = ?
                             AND node ->> 'handlerKey' = ?
                       )
                FROM domain_job domain
                JOIN app.ai_profile_version profile
                  ON profile.profile_version_id = domain.profile_version_id
                """, (resultSet, row) -> new DomainJob(
                        resultSet.getObject(1, UUID.class),
                        resultSet.getObject(2, UUID.class),
                        resultSet.getBoolean(3)),
                report.jobId(), report.jobId(), report.nodeId(), report.nodeType(),
                report.handlerKey());
        if (jobs.size() != 1) {
            throw notFound();
        }
        DomainJob job = jobs.get(0);
        if (!job.traceId().equals(report.traceId())
                || !job.profileVersionId().equals(report.profileVersionId())
                || !job.nodeMatches()) {
            throw new AiJobMonitoringException(
                    "MONITORING_IDENTITY_CONFLICT",
                    "The monitoring report does not match the Spring-owned Job.",
                    HttpStatus.CONFLICT);
        }
    }

    private List<LatestNodeState> latestNodes(UUID jobId, UUID profileVersionId) {
        return jdbc.query("""
                SELECT node.value ->> 'id', node.value ->> 'type',
                       node.value ->> 'handlerKey', occurrence.status,
                       occurrence.pipeline_attempt, occurrence.execution_attempt,
                       occurrence.node_sequence, occurrence.last_reported_at
                FROM app.ai_profile_version profile
                CROSS JOIN LATERAL jsonb_array_elements(
                    profile.snapshot_json -> 'nodes') WITH ORDINALITY AS node(value, ordinal)
                LEFT JOIN LATERAL (
                    SELECT status, pipeline_attempt, execution_attempt,
                           node_sequence, last_reported_at
                    FROM app.ai_job_node_occurrence
                    WHERE job_id = ? AND profile_version_id = profile.profile_version_id
                      AND node_id = node.value ->> 'id'
                    ORDER BY pipeline_attempt DESC, execution_attempt DESC,
                             node_sequence DESC
                    LIMIT 1
                ) occurrence ON TRUE
                WHERE profile.profile_version_id = ?
                ORDER BY node.ordinal
                """, (resultSet, row) -> {
                    String status = resultSet.getString(4);
                    return new LatestNodeState(
                            resultSet.getString(1), resultSet.getString(2),
                            resultSet.getString(3),
                            status == null ? NodeDisplayStatus.NOT_STARTED
                                    : NodeDisplayStatus.valueOf(status),
                            (Integer) resultSet.getObject(5),
                            (Integer) resultSet.getObject(6),
                            (Long) resultSet.getObject(7),
                            instant(resultSet.getTimestamp(8)));
                }, jobId, profileVersionId);
    }

    private static String summarySql() {
        return """
                WITH domain_jobs AS (
                    SELECT job_id, status AS domain_status,
                           status IN ('COMPLETED', 'FAILED', 'CANCELLED', 'EXPIRED')
                               AS domain_terminal,
                           state_version
                    FROM app.coding_job
                    UNION ALL
                    SELECT job_id, status AS domain_status,
                           status IN ('COMPLETED', 'REJECTED') AS domain_terminal,
                           state_version
                    FROM app.natural_cms_job
                )
                SELECT monitoring.job_id, monitoring.trace_id,
                       monitoring.profile_version_id, profile.profile_key,
                       profile.profile_version, domain.domain_status,
                       domain.domain_terminal, domain.state_version,
                       monitoring.pipeline_attempt, monitoring.execution_attempt,
                       monitoring.monitor_status, monitoring.monitor_revision,
                       monitoring.current_node_id, monitoring.current_node_sequence,
                       monitoring.current_node_type, monitoring.current_handler_key,
                       monitoring.current_node_status, monitoring.updated_at
                FROM app.ai_job_monitoring_state monitoring
                JOIN domain_jobs domain ON domain.job_id = monitoring.job_id
                JOIN app.ai_profile_version profile
                  ON profile.profile_version_id = monitoring.profile_version_id
                """;
    }

    private static JobSummary summary(java.sql.ResultSet resultSet)
            throws java.sql.SQLException {
        UUID profileVersionId = resultSet.getObject(3, UUID.class);
        String profileKey = resultSet.getString(4);
        return new JobSummary(
                resultSet.getObject(1, UUID.class),
                resultSet.getObject(2, UUID.class),
                profileVersionId,
                profileKey,
                resultSet.getInt(5),
                resultSet.getString(6),
                resultSet.getBoolean(7),
                resultSet.getLong(8),
                resultSet.getInt(9),
                resultSet.getInt(10),
                NodeStatus.valueOf(resultSet.getString(11)),
                resultSet.getLong(12),
                new CurrentNode(
                        resultSet.getString(13), resultSet.getLong(14),
                        resultSet.getString(15), resultSet.getString(16),
                        NodeStatus.valueOf(resultSet.getString(17))),
                "/api/admin/ai/profile-versions?profileKey=" + profileKey,
                "/api/admin/ai/profile-versions/" + profileVersionId + "/editor-layout",
                resultSet.getTimestamp(18).toInstant());
    }

    private static NodeOccurrence occurrence(java.sql.ResultSet resultSet)
            throws java.sql.SQLException {
        UUID jobId = resultSet.getObject(1, UUID.class);
        int pipelineAttempt = resultSet.getInt(3);
        int executionAttempt = resultSet.getInt(4);
        long nodeSequence = resultSet.getLong(6);
        return new NodeOccurrence(
                jobId, resultSet.getObject(2, UUID.class), pipelineAttempt,
                executionAttempt, resultSet.getString(5), nodeSequence,
                resultSet.getObject(7, UUID.class), resultSet.getString(8),
                resultSet.getString(9), resultSet.getString(10),
                NodeStatus.valueOf(resultSet.getString(11)),
                instant(resultSet.getTimestamp(12)), instant(resultSet.getTimestamp(13)),
                instant(resultSet.getTimestamp(14)), instant(resultSet.getTimestamp(15)),
                resultSet.getString(16),
                "/api/admin/ai/monitoring/jobs/" + jobId + "/occurrences/"
                        + pipelineAttempt + "/" + executionAttempt + "/" + nodeSequence
                        + "/observations",
                resultSet.getTimestamp(17).toInstant());
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static StateRow state(java.sql.ResultSet resultSet, boolean applied)
            throws java.sql.SQLException {
        return new StateRow(
                resultSet.getLong(1), resultSet.getInt(2), resultSet.getInt(3),
                resultSet.getString(4), resultSet.getLong(5),
                resultSet.getTimestamp(6).toInstant(), applied);
    }

    private static boolean sameOccurrence(StateRow state, OccurrenceRow occurrence) {
        return state.pipelineAttempt() == occurrence.pipelineAttempt()
                && state.executionAttempt() == occurrence.executionAttempt()
                && state.nodeSequence() == occurrence.nodeSequence()
                && state.nodeId().equals(occurrence.nodeId());
    }

    private static NodeStatus monitorStatus(NodeStatus nodeStatus, String nodeType) {
        if (nodeStatus == NodeStatus.COMPLETED && !"end".equals(nodeType)) {
            return NodeStatus.RUNNING;
        }
        return nodeStatus;
    }

    private static int limit(Integer value, int defaultValue, int maximum) {
        if (value == null) {
            return defaultValue;
        }
        if (value < 1 || value > maximum) {
            throw new IllegalArgumentException("limit is outside the supported range.");
        }
        return value;
    }

    private static AiJobMonitoringException notFound() {
        return new AiJobMonitoringException(
                "MONITORING_JOB_NOT_FOUND", "The monitored Job was not found.",
                HttpStatus.NOT_FOUND);
    }

    private static byte[] credentialDigest(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")
                || authorization.length() <= 7) {
            throw new AiJobMonitoringException(
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

    private record DomainJob(
            UUID traceId, UUID profileVersionId, boolean nodeMatches) { }

    private record OccurrenceRow(
            UUID jobId,
            UUID profileVersionId,
            int pipelineAttempt,
            int executionAttempt,
            String nodeId,
            long nodeSequence,
            NodeStatus status,
            Instant lastReportedAt,
            boolean applied) { }

    private record StateRow(
            long monitorRevision,
            int pipelineAttempt,
            int executionAttempt,
            String nodeId,
            long nodeSequence,
            Instant updatedAt,
            boolean applied) { }
}

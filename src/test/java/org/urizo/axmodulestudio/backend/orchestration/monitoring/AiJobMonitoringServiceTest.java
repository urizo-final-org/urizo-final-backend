package org.urizo.axmodulestudio.backend.orchestration.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.urizo.axmodulestudio.backend.orchestration.monitoring.AiJobMonitoringContract.NodeOccurrenceReport;
import org.urizo.axmodulestudio.backend.orchestration.monitoring.AiJobMonitoringContract.NodeStatus;
import org.urizo.axmodulestudio.backend.orchestration.monitoring.AiJobMonitoringContract.ReportResponse;

class AiJobMonitoringServiceTest {

    private static final UUID JOB_ID =
            UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID TRACE_ID =
            UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID PROFILE_VERSION_ID =
            UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final Instant NOW = Instant.parse("2026-09-07T08:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void nonEndCompletionKeepsTheSeparateMonitorRunningAndUsesMonotonicSql() {
        FakeDatabase database = new FakeDatabase(
                true, NodeStatus.COMPLETED, true,
                8, 2, 3, "analyze", 7);
        AiJobMonitoringService service = service(database);

        ReportResponse response = service.report(
                "Bearer service-token", report(NodeStatus.COMPLETED, "check", 7));

        assertThat(response.monitorRevision()).isEqualTo(8);
        assertThat(response.applied()).isTrue();
        assertThat(response.current()).isTrue();
        assertThat(response.status()).isEqualTo(NodeStatus.COMPLETED);
        SqlCall occurrence = database.callContaining(
                "INSERT INTO app.ai_job_node_occurrence");
        SqlCall state = database.callContaining("INSERT INTO app.ai_job_monitoring_state");
        assertThat(occurrence.sql())
                .contains("CASE WHEN ? = 'RUNNING'\n"
                                + "        THEN CAST(? AS TIMESTAMPTZ) ELSE NULL END",
                        "CASE WHEN ? = 'WAITING_APPROVAL'\n"
                                + "        THEN CAST(? AS TIMESTAMPTZ) ELSE NULL END",
                        "CASE WHEN ? = 'COMPLETED'\n"
                                + "        THEN CAST(? AS TIMESTAMPTZ) ELSE NULL END",
                        "CASE WHEN ? = 'FAILED'\n"
                                + "        THEN CAST(? AS TIMESTAMPTZ) ELSE NULL END")
                .contains("WHEN 'RUNNING' THEN 1 WHEN 'WAITING_APPROVAL' THEN 2 ELSE 3 END")
                .contains("EXCLUDED.status = app.ai_job_node_occurrence.status")
                .contains("app.ai_job_node_occurrence.observation_trace_id IS NULL")
                .contains("EXCLUDED.observation_trace_id IS NOT NULL")
                .contains("GREATEST(")
                .doesNotContain("state_version");
        assertThat(state.sql())
                .contains("monitor_revision = app.ai_job_monitoring_state.monitor_revision + 1")
                .contains("(EXCLUDED.pipeline_attempt, EXCLUDED.execution_attempt,")
                .contains("> (app.ai_job_monitoring_state.pipeline_attempt,")
                .contains("app.ai_job_monitoring_state.observation_trace_id IS NULL")
                .doesNotContain("UPDATE app.coding_job", "UPDATE app.natural_cms_job");
        assertThat(state.parameters().get(6)).isEqualTo("RUNNING");
    }

    @Test
    void endCompletionMakesTheMonitoringStateTerminal() {
        FakeDatabase database = new FakeDatabase(
                true, NodeStatus.COMPLETED, true,
                9, 2, 3, "finish", 8);

        ReportResponse response = service(database).report(
                "Bearer service-token", report(NodeStatus.COMPLETED, "end", 8));

        assertThat(response.current()).isTrue();
        assertThat(database.callContaining("INSERT INTO app.ai_job_monitoring_state")
                .parameters().get(6)).isEqualTo("COMPLETED");
    }

    @Test
    void aLateOlderOccurrenceIsStoredWithoutRollingBackTheCurrentPointer() {
        FakeDatabase database = new FakeDatabase(
                true, NodeStatus.COMPLETED, false,
                11, 2, 3, "finish", 9);

        ReportResponse response = service(database).report(
                "Bearer service-token", report(NodeStatus.COMPLETED, "check", 7));

        assertThat(response.applied()).isTrue();
        assertThat(response.current()).isFalse();
        assertThat(response.monitorRevision()).isEqualTo(12);
        assertThat(database.sql()).anyMatch(sql ->
                sql.contains("FROM app.ai_job_monitoring_state WHERE job_id = ?"));
        assertThat(database.callContaining("UPDATE app.ai_job_monitoring_state").sql())
                .contains("monitor_revision = monitor_revision + 1")
                .doesNotContain("current_node_id =", "current_node_status =");
    }

    @Test
    void aLateRunningRetryCannotDowngradeTheCompletedOccurrence() {
        FakeDatabase database = new FakeDatabase(
                false, NodeStatus.COMPLETED, false,
                11, 2, 3, "analyze", 7);

        ReportResponse response = service(database).report(
                "Bearer service-token", report(NodeStatus.RUNNING, "check", 7));

        assertThat(response.applied()).isFalse();
        assertThat(response.current()).isTrue();
        assertThat(response.status()).isEqualTo(NodeStatus.COMPLETED);
        assertThat(database.sql()).anyMatch(sql ->
                sql.contains("FROM app.ai_job_node_occurrence")
                        && sql.contains("node_sequence = ?"));
    }

    @Test
    void aLateCompletionCannotReplaceTheFirstFailedTerminalOccurrence() {
        FakeDatabase database = new FakeDatabase(
                false, NodeStatus.FAILED, false,
                11, 2, 3, "analyze", 7);

        ReportResponse response = service(database).report(
                "Bearer service-token", report(NodeStatus.COMPLETED, "check", 7));

        assertThat(response.applied()).isFalse();
        assertThat(response.monitorRevision()).isEqualTo(11);
        assertThat(response.current()).isTrue();
        assertThat(response.status()).isEqualTo(NodeStatus.FAILED);
        assertThat(database.callContaining("INSERT INTO app.ai_job_monitoring_state")
                .parameters().get(2)).isNull();
    }

    @Test
    void aLateFailureCannotReplaceTheFirstCompletedTerminalOccurrence() {
        FakeDatabase database = new FakeDatabase(
                false, NodeStatus.COMPLETED, false,
                11, 2, 3, "analyze", 7);

        ReportResponse response = service(database).report(
                "Bearer service-token", report(NodeStatus.FAILED, "check", 7));

        assertThat(response.applied()).isFalse();
        assertThat(response.monitorRevision()).isEqualTo(11);
        assertThat(response.current()).isTrue();
        assertThat(response.status()).isEqualTo(NodeStatus.COMPLETED);
        assertThat(database.callContaining("INSERT INTO app.ai_job_monitoring_state")
                .parameters().get(2)).isNull();
    }

    @Test
    void sameTerminalStatusCanHydrateTheObservationTrace() {
        FakeDatabase database = new FakeDatabase(
                true, NodeStatus.FAILED, true,
                12, 2, 3, "analyze", 7);

        ReportResponse response = service(database).report(
                "Bearer service-token", report(NodeStatus.FAILED, "check", 7));

        assertThat(response.applied()).isTrue();
        assertThat(response.monitorRevision()).isEqualTo(12);
        assertThat(response.current()).isTrue();
        assertThat(response.status()).isEqualTo(NodeStatus.FAILED);
        assertThat(database.callContaining("INSERT INTO app.ai_job_node_occurrence").sql())
                .contains("EXCLUDED.status = app.ai_job_node_occurrence.status")
                .contains("app.ai_job_node_occurrence.observation_trace_id IS NULL")
                .contains("EXCLUDED.observation_trace_id IS NOT NULL");
        assertThat(database.callContaining("INSERT INTO app.ai_job_monitoring_state")
                .parameters().get(2)).isEqualTo("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    }

    private static AiJobMonitoringService service(FakeDatabase database) {
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        return new AiJobMonitoringService(database.jdbc(), transactions, CLOCK);
    }

    private static NodeOccurrenceReport report(
            NodeStatus status, String nodeType, long nodeSequence) {
        return new NodeOccurrenceReport(
                "1.0", JOB_ID, TRACE_ID, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                PROFILE_VERSION_ID, 2, 3,
                "end".equals(nodeType) ? "finish" : "analyze",
                nodeSequence, nodeType,
                "end".equals(nodeType) ? "fixture.end" : "fixture.analyze",
                status, NOW,
                status == NodeStatus.FAILED ? "NODE_EXECUTION_FAILED" : null);
    }

    private record SqlCall(String sql, List<Object> parameters) { }

    private static final class FakeDatabase {

        private final boolean occurrenceChanged;
        private final NodeStatus occurrenceStatus;
        private final boolean stateChanged;
        private final long monitorRevision;
        private final int currentPipelineAttempt;
        private final int currentExecutionAttempt;
        private final String currentNodeId;
        private final long currentNodeSequence;
        private final List<SqlCall> calls = new ArrayList<>();
        private final JdbcTemplate jdbc;

        FakeDatabase(
                boolean occurrenceChanged,
                NodeStatus occurrenceStatus,
                boolean stateChanged,
                long monitorRevision,
                int currentPipelineAttempt,
                int currentExecutionAttempt,
                String currentNodeId,
                long currentNodeSequence) {
            this.occurrenceChanged = occurrenceChanged;
            this.occurrenceStatus = occurrenceStatus;
            this.stateChanged = stateChanged;
            this.monitorRevision = monitorRevision;
            this.currentPipelineAttempt = currentPipelineAttempt;
            this.currentExecutionAttempt = currentExecutionAttempt;
            this.currentNodeId = currentNodeId;
            this.currentNodeSequence = currentNodeSequence;
            this.jdbc = mock(JdbcTemplate.class, this::answer);
        }

        JdbcTemplate jdbc() {
            return jdbc;
        }

        List<String> sql() {
            return calls.stream().map(SqlCall::sql).toList();
        }

        SqlCall callContaining(String text) {
            return calls.stream()
                    .filter(call -> call.sql().contains(text))
                    .findFirst()
                    .orElseThrow();
        }

        private Object answer(InvocationOnMock invocation) throws Throwable {
            String method = invocation.getMethod().getName();
            if ("update".equals(method)) {
                return 1;
            }
            if (!"query".equals(method) && !"queryForObject".equals(method)) {
                return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
            }
            String sql = invocation.getArgument(0);
            calls.add(new SqlCall(sql, new ArrayList<>(Arrays.asList(
                    Arrays.copyOfRange(invocation.getArguments(), 2,
                            invocation.getArguments().length)))));
            if (sql.contains("FROM app.coding_service_credential")) {
                return rows(invocation, credentialResult());
            }
            if (sql.contains("WITH domain_job AS")) {
                return rows(invocation, domainJobResult());
            }
            if (sql.contains("INSERT INTO app.ai_job_node_occurrence")) {
                return occurrenceChanged
                        ? rows(invocation, occurrenceResult()) : List.of();
            }
            if (sql.contains("INSERT INTO app.ai_job_monitoring_state")) {
                return stateChanged ? rows(invocation, stateResult()) : List.of();
            }
            if (sql.contains("UPDATE app.ai_job_monitoring_state")) {
                return mapped(invocation, stateResult(monitorRevision + 1));
            }
            if (sql.contains("FROM app.ai_job_node_occurrence")) {
                return mapped(invocation, occurrenceResult());
            }
            if (sql.contains("FROM app.ai_job_monitoring_state")) {
                return mapped(invocation, stateResult());
            }
            throw new AssertionError("Unexpected monitoring SQL: " + sql);
        }

        private static List<Object> rows(
                InvocationOnMock invocation, ResultSet resultSet) throws Exception {
            return List.of(mapped(invocation, resultSet));
        }

        @SuppressWarnings("unchecked")
        private static Object mapped(
                InvocationOnMock invocation, ResultSet resultSet) throws Exception {
            RowMapper<Object> mapper = invocation.getArgument(1);
            return mapper.mapRow(resultSet, 0);
        }

        private static ResultSet credentialResult() throws Exception {
            ResultSet result = mock(ResultSet.class);
            when(result.getObject(1, UUID.class)).thenReturn(UUID.randomUUID());
            return result;
        }

        private static ResultSet domainJobResult() throws Exception {
            ResultSet result = mock(ResultSet.class);
            when(result.getObject(1, UUID.class)).thenReturn(TRACE_ID);
            when(result.getObject(2, UUID.class)).thenReturn(PROFILE_VERSION_ID);
            when(result.getBoolean(3)).thenReturn(true);
            return result;
        }

        private ResultSet occurrenceResult() throws Exception {
            ResultSet result = mock(ResultSet.class);
            when(result.getString(1)).thenReturn(occurrenceStatus.name());
            when(result.getString(2)).thenReturn(occurrenceChanged
                    ? "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" : null);
            when(result.getTimestamp(3)).thenReturn(Timestamp.from(NOW));
            return result;
        }

        private ResultSet stateResult() throws Exception {
            return stateResult(monitorRevision);
        }

        private ResultSet stateResult(long revision) throws Exception {
            ResultSet result = mock(ResultSet.class);
            when(result.getLong(1)).thenReturn(revision);
            when(result.getInt(2)).thenReturn(currentPipelineAttempt);
            when(result.getInt(3)).thenReturn(currentExecutionAttempt);
            when(result.getString(4)).thenReturn(currentNodeId);
            when(result.getLong(5)).thenReturn(currentNodeSequence);
            when(result.getTimestamp(6)).thenReturn(Timestamp.from(NOW));
            return result;
        }
    }
}

package org.urizo.axmodulestudio.backend.coding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.ignoreStubs;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.urizo.axmodulestudio.backend.coding.dto.CodingJobLifecycleContract.Status;
import org.urizo.axmodulestudio.backend.coding.dto.CodingJobLifecycleContract.TransitionRequest;

class CodingJobExpiryReconcilerTest {

    private static final Instant NOW = Instant.parse("2026-09-09T09:00:00Z");
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final CodingJobLifecycleService lifecycle = mock(CodingJobLifecycleService.class);
    private final CodingJobExpiryReconciler reconciler = new CodingJobExpiryReconciler(
            jdbc, lifecycle, Clock.fixed(NOW, ZoneOffset.UTC));
    private final UUID jobId = UUID.randomUUID();
    private final UUID traceId = UUID.randomUUID();

    @Test
    void queriesOnlyDueAuthoritativeNonterminalJobsInABoundedStableOrder() throws Exception {
        candidates();
        reconciler.reconcile();
        verifyNoInteractions(lifecycle);
        verifyNoMoreInteractions(ignoreStubs(jdbc));
    }

    @Test
    void delegatesOnlyExpiryWithTheOriginalTraceAndExpectedVersion() throws Exception {
        candidates(jobId);
        reconciler.reconcile();
        verify(lifecycle).transition(jobId, traceId, key(jobId), request());
        verifyNoMoreInteractions(ignoreStubs(lifecycle, jdbc));
    }

    @Test
    void repeatsTheSameIdempotencyKeyAndPayloadForConcurrentOrRepeatedScans() throws Exception {
        candidates(jobId);
        reconciler.reconcile();
        reconciler.reconcile();
        verify(lifecycle, times(2)).transition(jobId, traceId, key(jobId), request());
        verifyNoMoreInteractions(ignoreStubs(lifecycle, jdbc));
    }

    @ParameterizedTest
    @ValueSource(strings = {"JOB_STATE_VERSION_CONFLICT", "JOB_TERMINAL", "JOB_NOT_EXPIRED",
            "JOB_NOT_FOUND", "IDEMPOTENCY_KEY_REUSED"})
    void leavesLifecycleRejectionsUntouchedAndContinuesWithTheNextJob(String code) throws Exception {
        UUID nextJob = UUID.randomUUID();
        candidates(jobId, nextJob);
        when(lifecycle.transition(eq(jobId), eq(traceId), anyString(), any()))
                .thenThrow(new CodingJobLifecycleException(code, "test rejection", HttpStatus.CONFLICT));
        reconciler.reconcile();
        verify(lifecycle).transition(jobId, traceId, key(jobId), request());
        verify(lifecycle).transition(nextJob, traceId, key(nextJob), request());
        verifyNoMoreInteractions(ignoreStubs(lifecycle, jdbc));
    }

    @Test
    void isolatesUnexpectedPerJobFailureWithoutSkippingTheRest() throws Exception {
        UUID nextJob = UUID.randomUUID();
        candidates(jobId, nextJob);
        when(lifecycle.transition(eq(jobId), eq(traceId), anyString(), any()))
                .thenThrow(new IllegalStateException("test failure"));
        reconciler.reconcile();
        verify(lifecycle).transition(nextJob, traceId, key(nextJob), request());
    }

    @ParameterizedTest
    @EnumSource(value = Status.class, names = {"PENDING", "RUNNING", "WAITING_APPROVAL"})
    void existingStateMachineAllowsExpiryAtAndAfterDeadlineButNeverBefore(Status current) {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                CodingJobStateMachine.requireTransition(current, Status.EXPIRED, NOW.minusNanos(1), NOW))
                .isInstanceOfSatisfying(CodingJobLifecycleException.class,
                        failure -> assertThat(failure.code()).isEqualTo("JOB_NOT_EXPIRED"));
        CodingJobStateMachine.requireTransition(current, Status.EXPIRED, NOW, NOW);
        CodingJobStateMachine.requireTransition(current, Status.EXPIRED, NOW.plusSeconds(1), NOW);
    }

    @ParameterizedTest
    @EnumSource(value = Status.class, names = {"COMPLETED", "FAILED", "CANCELLED", "EXPIRED"})
    void existingStateMachinePreservesEveryTerminalState(Status current) {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                CodingJobStateMachine.requireTransition(current, Status.EXPIRED, NOW, NOW.minusSeconds(1)))
                .isInstanceOfSatisfying(CodingJobLifecycleException.class,
                        failure -> assertThat(failure.code()).isEqualTo("JOB_TERMINAL"));
    }

    @SuppressWarnings("unchecked")
    private void candidates(UUID... ids) throws Exception {
        when(jdbc.query(anyString(), any(RowMapper.class), any(Timestamp.class)))
                .thenAnswer(invocation -> {
                    String sql = invocation.getArgument(0);
                    assertThat(sql).contains("SELECT job_id, trace_id, state_version",
                            "authority_source = 'SPRING_CONTROL_PLANE'",
                            "status IN ('PENDING', 'RUNNING', 'WAITING_APPROVAL')",
                            "expires_at <= ?", "ORDER BY expires_at, job_id", "LIMIT 100")
                            .doesNotContain("UPDATE", "DELETE", "INSERT");
                    assertThat(invocation.<Timestamp>getArgument(2).toInstant()).isEqualTo(NOW);
                    RowMapper<Object> mapper = invocation.getArgument(1);
                    List<Object> rows = new ArrayList<>();
                    for (UUID id : ids) {
                        ResultSet row = mock(ResultSet.class);
                        when(row.getObject("job_id", UUID.class)).thenReturn(id);
                        when(row.getObject("trace_id", UUID.class)).thenReturn(traceId);
                        when(row.getInt("state_version")).thenReturn(3);
                        rows.add(mapper.mapRow(row, rows.size()));
                    }
                    return rows;
                });
        // The only JDBC interaction allowed is the read-only candidate query.
        org.mockito.Mockito.clearInvocations(jdbc);
    }

    private static String key(UUID id) { return "job.expire." + id + ".v3"; }

    private static TransitionRequest request() {
        return new TransitionRequest("1.0", 3, Status.EXPIRED, null);
    }
}

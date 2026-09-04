package org.urizo.axmodulestudio.backend.coding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.urizo.axmodulestudio.backend.auth.entity.AdminRole;
import org.urizo.axmodulestudio.backend.coding.dto.CodingJobLifecycleContract;

/**
 * Cancelling is not rejecting, and the difference is the whole reason this exists. A rejection
 * at the preview stage opens the next attempt - that is its job - so an administrator who has
 * changed their mind entirely had no way to say so and had to reject three times, sitting
 * through a full model run between each. Measured on 2026-09-04, where the only way out was the
 * development-only transition endpoint that no demo can use.
 *
 * <p>RUNNING is refused rather than forced. The tool gateway requires the Job to read RUNNING
 * before it will execute anything, so flipping the status underneath a working model does not
 * stop it cleanly: the next tool call fails and the worker then tries to record a failure that
 * the state machine will not accept from a cancelled Job. Telling the person to wait is honest;
 * pretending to stop it is not.
 */
class CodingConsoleCancelTest {

    private static final UUID JOB = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID TRACE = UUID.fromString("22222222-2222-4222-8222-222222222222");

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final CodingJobLifecycleService lifecycle = mock(CodingJobLifecycleService.class);
    private final CodingConsoleService service =
            new CodingConsoleService(jdbc, new ObjectMapper(), lifecycle);

    /** Answers the cancel lookup with one row in the given state; every other query is empty. */
    @SuppressWarnings("unchecked")
    private void jobIs(String status, int stateVersion) {
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> {
                    String sql = invocation.getArgument(0);
                    if (!sql.contains("SELECT trace_id, status, state_version")) {
                        // detail() runs afterwards and is not what this test is about.
                        return List.of();
                    }
                    RowMapper<Object> mapper = invocation.getArgument(1);
                    ResultSet row = mock(ResultSet.class);
                    when(row.getObject("trace_id", UUID.class)).thenReturn(TRACE);
                    when(row.getString("status")).thenReturn(status);
                    when(row.getInt("state_version")).thenReturn(stateVersion);
                    return List.of(mapper.mapRow(row, 0));
                });
    }

    @Test
    @DisplayName("승인을 기다리는 요청은 취소된다. AI 를 다시 돌리지 않는다")
    void cancelsAJobThatIsWaitingForApproval() {
        jobIs("WAITING_APPROVAL", 6);

        service.cancel(JOB, "cancel-key-0001", AdminRole.SUPER_ADMIN);

        ArgumentCaptor<CodingJobLifecycleContract.TransitionRequest> sent =
                ArgumentCaptor.forClass(CodingJobLifecycleContract.TransitionRequest.class);
        verify(lifecycle).transition(eq(JOB), eq(TRACE), eq("cancel-key-0001"), sent.capture());
        assertThat(sent.getValue().targetStatus())
                .isEqualTo(CodingJobLifecycleContract.Status.CANCELLED);
        // The version read a moment ago, so a Job that moved in between is refused by the
        // state machine instead of being cancelled from a stale view of it.
        assertThat(sent.getValue().expectedStateVersion()).isEqualTo(6);
        assertThat(sent.getValue().failure()).isNull();
    }

    @Test
    @DisplayName("아직 시작 전인 요청도 취소된다")
    void cancelsAPendingJob() {
        jobIs("PENDING", 1);

        service.cancel(JOB, "cancel-key-0002", AdminRole.GENERAL_ADMIN);

        verify(lifecycle).transition(eq(JOB), eq(TRACE), eq("cancel-key-0002"), any());
    }

    @Test
    @DisplayName("AI 가 작업 중이면 거절하고, 기다리면 된다고 알려준다")
    void refusesWhileTheModelIsWorking() {
        jobIs("RUNNING", 4);

        assertThatThrownBy(() -> service.cancel(JOB, "cancel-key-0003", AdminRole.SUPER_ADMIN))
                .isInstanceOf(CodingJobLifecycleException.class)
                .hasMessageContaining("작업이 끝나면 취소할 수 있습니다")
                .extracting(failure -> ((CodingJobLifecycleException) failure).code())
                .isEqualTo("CODING_JOB_IS_RUNNING");

        verify(lifecycle, never()).transition(any(), any(), anyString(), any());
    }

    @Test
    @DisplayName("이미 끝난 요청은 취소할 것이 없다")
    void refusesAJobThatHasAlreadyFinished() {
        for (String finished : List.of("COMPLETED", "FAILED", "CANCELLED", "EXPIRED")) {
            jobIs(finished, 9);

            assertThatThrownBy(() -> service.cancel(JOB, "cancel-key-0004", AdminRole.SUPER_ADMIN))
                    .as("status %s", finished)
                    .isInstanceOf(CodingJobLifecycleException.class)
                    .extracting(failure -> ((CodingJobLifecycleException) failure).code())
                    .isEqualTo("CODING_JOB_ALREADY_FINISHED");
        }
        verify(lifecycle, never()).transition(any(), any(), anyString(), any());
    }

    @Test
    @DisplayName("없는 요청은 오류가 아니라 없음으로 답한다")
    @SuppressWarnings("unchecked")
    void answersNullForAnUnknownJob() {
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());

        assertThat(service.cancel(JOB, "cancel-key-0005", AdminRole.SUPER_ADMIN)).isNull();
        verify(lifecycle, never()).transition(any(), any(), anyString(), any());
    }
}

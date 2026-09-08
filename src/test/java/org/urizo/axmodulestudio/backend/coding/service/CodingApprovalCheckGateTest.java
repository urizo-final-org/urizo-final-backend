package org.urizo.axmodulestudio.backend.coding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.urizo.axmodulestudio.backend.auth.entity.AdminRole;
import org.urizo.axmodulestudio.backend.auth.security.AuthenticatedActor;
import org.urizo.axmodulestudio.backend.coding.dto.CodingHandlerContract;
import org.urizo.axmodulestudio.backend.coding.dto.CodingJobLifecycleContract;
import org.urizo.axmodulestudio.backend.coding.repository.CodingJobLifecycleRepository;

/**
 * The preview approval, against the checks the guardrail screen promises are always on.
 *
 * <p>Written from Job 9b55bb27. Its TEST failed and both approvals went through anyway, because
 * nothing on the approval path had ever read the runner's rows. The guardrail screen said
 * "빌드 통과 필수 · 테스트 통과 필수 — 항상 켜져 있으며 끌 수 없습니다" the whole time.
 *
 * <p>These go through {@code decide} rather than through the check alone, on purpose. A gate
 * that is correct but not wired in reads exactly like a gate.
 */
class CodingApprovalCheckGateTest {

    private static final UUID JOB_ID = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID TRACE_ID = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    private static final UUID ACTOR_ID = UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
    private static final String CANDIDATE = "sha1:1111111111111111111111111111111111111111";
    private static final String VALIDATION =
            "sha256:2222222222222222222222222222222222222222222222222222222222222222";
    private static final int STATE_VERSION = 8;
    private static final int PIPELINE_ATTEMPT = 1;
    private static final int STAGE_ROUND = 1;

    @Test
    void refusesTheApprovalWhileACheckOfThisJobIsRecordedFailed() {
        assertThatThrownBy(() -> decide(1, CodingHandlerContract.Decision.APPROVED))
                .isInstanceOf(CodingJobLifecycleException.class)
                .hasMessageContaining("검사를 통과하지 못해")
                // The reader cannot act on "테스트 실패" alone, so the message says what they can do.
                .hasMessageContaining("반려하면");
    }

    @Test
    void letsTheApprovalThroughWhenEveryCheckPassed() {
        assertThatCode(() -> decide(0, CodingHandlerContract.Decision.APPROVED))
                .doesNotThrowAnyException();
    }

    @Test
    void doesNotGateAJobThatHasNoCheckRowsAtAll() {
        // Queued rows carry the workspace id only for Jobs the current intake created. Refusing
        // an older Job would turn "we have no record" into "it failed", which is not the same
        // thing - and is the rule the preview link already follows.
        assertThatCode(() -> decide(null, CodingHandlerContract.Decision.APPROVED))
                .doesNotThrowAnyException();
    }

    @Test
    void neverBlocksARejection() {
        // Calling a result off has to stay possible whatever the checks say. It is also the way
        // out when a check is wrong: rejecting a preview opens the next attempt.
        assertThatCode(() -> decide(1, CodingHandlerContract.Decision.REJECTED))
                .doesNotThrowAnyException();
    }

    /**
     * One CANDIDATE decision through the real path.
     *
     * @param failedChecks what the runner-task count returns, or null for a Job with no rows
     */
    @SuppressWarnings("unchecked")
    private static void decide(Integer failedChecks, CodingHandlerContract.Decision decision)
            throws Exception {
        Instant now = Instant.parse("2026-09-08T12:00:00Z");
        UUID approvalId = CodingApprovalId.forStage(
                JOB_ID,
                PIPELINE_ATTEMPT,
                "preview_approval",
                CodingHandlerContract.ApprovalStage.CANDIDATE,
                STAGE_ROUND);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        ObjectNode outcome = mapper.createObjectNode()
                .put("schemaVersion", "1.0")
                .put("jobId", JOB_ID.toString())
                .put("traceId", TRACE_ID.toString())
                .put("stateVersion", STATE_VERSION)
                .put("status", "WAITING_APPROVAL");
        outcome.putObject("pendingApproval")
                .put("approvalId", approvalId.toString())
                .put("nodeId", "preview_approval")
                .put("stage", "CANDIDATE")
                .put("stageRound", STAGE_ROUND)
                .put("requiredRole", "GENERAL_ADMIN");

        JdbcTemplate jdbc = mock(JdbcTemplate.class, invocation -> {
            String method = invocation.getMethod().getName();
            Object[] arguments = invocation.getArguments();
            if ("update".equals(method)) {
                return 1;
            }
            if ("queryForObject".equals(method) && arguments[0] instanceof String sql) {
                // Checked first: this one also counts, and would otherwise be answered by the
                // idempotency branch below.
                if (sql.contains("app.coding_runner_task")) {
                    return failedChecks;
                }
                if (sql.contains("SELECT status FROM app.coding_job")) {
                    return "WAITING_APPROVAL";
                }
                if (sql.contains("SELECT status FROM app.coding_pipeline_attempt")) {
                    return "ACTIVE";
                }
                if (sql.contains("SELECT COUNT(*)")) {
                    return 0;
                }
            }
            if ("query".equals(method)
                    && arguments.length > 1
                    && arguments[0] instanceof String sql
                    && arguments[1] instanceof RowMapper<?> rowMapper) {
                ResultSet row = mock(ResultSet.class);
                if (sql.contains("FROM app.coding_worker_command")) {
                    when(row.getString("response_json")).thenReturn(outcome.toString());
                    when(row.getString(2)).thenReturn(outcome.toString());
                    return List.of(rowMapper.mapRow(row, 0));
                }
                if (sql.contains("SELECT trace_id, actor_id, status, state_version, policy_hash")) {
                    when(row.getObject("trace_id", UUID.class)).thenReturn(TRACE_ID);
                    when(row.getObject("actor_id", UUID.class)).thenReturn(ACTOR_ID);
                    when(row.getString("status")).thenReturn("WAITING_APPROVAL");
                    when(row.getInt("state_version")).thenReturn(STATE_VERSION);
                    when(row.getString("policy_hash")).thenReturn(
                            "sha256:3333333333333333333333333333333333333333333333333333333333333333");
                    return List.of(rowMapper.mapRow(row, 0));
                }
                if (sql.contains("FROM app.coding_pipeline_attempt")
                        && sql.contains("ORDER BY pipeline_attempt DESC")) {
                    when(row.getInt("pipeline_attempt")).thenReturn(PIPELINE_ATTEMPT);
                    return List.of(rowMapper.mapRow(row, 0));
                }
                if (sql.contains("handler_key = 'coding.preview'")) {
                    when(row.getString("candidate_sha")).thenReturn(CANDIDATE);
                    when(row.getString("validation_hash")).thenReturn(VALIDATION);
                    return List.of(rowMapper.mapRow(row, 0));
                }
                if (sql.contains("handler_key = ?")) {
                    when(row.getString("result_port")).thenReturn("feasible");
                    return List.of(rowMapper.mapRow(row, 0));
                }
                return List.of();
            }
            return Answers.RETURNS_DEFAULTS.answer(invocation);
        });

        TransactionTemplate transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        CodingJobLifecycleRepository lifecycle = mock(CodingJobLifecycleRepository.class);
        CodingJobLifecycleRequestDigester lifecycleDigester =
                mock(CodingJobLifecycleRequestDigester.class);
        when(lifecycleDigester.transition(
                eq(JOB_ID), eq(TRACE_ID), any(CodingJobLifecycleContract.TransitionRequest.class)))
                .thenReturn(new byte[32]);
        CodingJobLifecycleContract.JobResponse transitioned =
                mock(CodingJobLifecycleContract.JobResponse.class);
        when(transitioned.stateVersion()).thenReturn(STATE_VERSION + 1);
        when(transitioned.status()).thenReturn(CodingJobLifecycleContract.Status.RUNNING);
        when(lifecycle.transition(
                eq(JOB_ID),
                eq(TRACE_ID),
                anyString(),
                any(byte[].class),
                any(CodingJobLifecycleContract.TransitionRequest.class)))
                .thenReturn(transitioned);

        CodingHandlerCommandService service = new CodingHandlerCommandService(
                jdbc,
                // The fixture stubs one template, so the worker-side reads use it too.
                jdbc,
                transactions,
                lifecycle,
                mock(CodingJobLifecycleService.class),
                lifecycleDigester,
                mock(GuardrailJobSnapshotWriter.class),
                mapper,
                Clock.fixed(now, ZoneOffset.UTC));

        CodingHandlerContract.ApprovalDecisionResponse response = service.decide(
                new AuthenticatedActor(ACTOR_ID, "gate-admin", AdminRole.GENERAL_ADMIN),
                JOB_ID,
                "approval.check-gate",
                new CodingHandlerContract.ApprovalDecisionRequest(
                        "1.0",
                        TRACE_ID,
                        STATE_VERSION,
                        PIPELINE_ATTEMPT,
                        approvalId,
                        "preview_approval",
                        CodingHandlerContract.ApprovalStage.CANDIDATE,
                        STAGE_ROUND,
                        CANDIDATE,
                        VALIDATION,
                        decision,
                        decision == CodingHandlerContract.Decision.REJECTED ? "다시 만들어 주세요" : null));

        assertThat(response.approvalId()).isEqualTo(approvalId);
    }
}

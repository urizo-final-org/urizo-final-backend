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

class CodingHandlerCommandServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void decisionUsesTheLatestWorkerOutcomeApprovalRoleInsteadOfTheFixedStageRole()
            throws Exception {
        UUID jobId = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        UUID traceId = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
        UUID actorId = UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
        String candidate = "sha1:1111111111111111111111111111111111111111";
        String validation =
                "sha256:2222222222222222222222222222222222222222222222222222222222222222";
        Instant now = Instant.parse("2026-08-31T12:00:00Z");
        int stateVersion = 8;
        int pipelineAttempt = 1;
        int stageRound = 1;
        UUID approvalId = CodingApprovalId.forStage(
                jobId,
                pipelineAttempt,
                "github_approval",
                CodingHandlerContract.ApprovalStage.GITHUB,
                stageRound);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        ObjectNode outcome = mapper.createObjectNode()
                .put("schemaVersion", "1.0")
                .put("jobId", jobId.toString())
                .put("traceId", traceId.toString())
                .put("stateVersion", stateVersion)
                .put("status", "WAITING_APPROVAL");
        outcome.putObject("pendingApproval")
                .put("approvalId", approvalId.toString())
                .put("nodeId", "github_approval")
                .put("stage", "GITHUB")
                .put("stageRound", stageRound)
                .put("requiredRole", "GENERAL_ADMIN");

        JdbcTemplate jdbc = mock(JdbcTemplate.class, invocation -> {
            String method = invocation.getMethod().getName();
            Object[] arguments = invocation.getArguments();
            if ("update".equals(method)) {
                return 1;
            }
            if ("queryForObject".equals(method) && arguments[0] instanceof String sql) {
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
                    when(row.getObject("trace_id", UUID.class)).thenReturn(traceId);
                    when(row.getObject("actor_id", UUID.class)).thenReturn(actorId);
                    when(row.getString("status")).thenReturn("WAITING_APPROVAL");
                    when(row.getInt("state_version")).thenReturn(stateVersion);
                    when(row.getString("policy_hash")).thenReturn(
                            "sha256:3333333333333333333333333333333333333333333333333333333333333333");
                    return List.of(rowMapper.mapRow(row, 0));
                }
                if (sql.contains("FROM app.coding_pipeline_attempt")
                        && sql.contains("ORDER BY pipeline_attempt DESC")) {
                    when(row.getInt("pipeline_attempt")).thenReturn(pipelineAttempt);
                    return List.of(rowMapper.mapRow(row, 0));
                }
                if (sql.contains("handler_key = 'coding.preview'")) {
                    when(row.getString("candidate_sha")).thenReturn(candidate);
                    when(row.getString("validation_hash")).thenReturn(validation);
                    return List.of(rowMapper.mapRow(row, 0));
                }
                if (sql.contains("handler_key = 'coding.pr_request'")) {
                    when(row.getString("candidate_sha")).thenReturn(candidate);
                    when(row.getString("validation_hash")).thenReturn(validation);
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
        CodingJobLifecycleService lifecycleService = mock(CodingJobLifecycleService.class);
        CodingJobLifecycleRequestDigester lifecycleDigester =
                mock(CodingJobLifecycleRequestDigester.class);
        when(lifecycleDigester.transition(
                eq(jobId), eq(traceId), any(CodingJobLifecycleContract.TransitionRequest.class)))
                .thenReturn(new byte[32]);
        CodingJobLifecycleContract.JobResponse transitioned =
                mock(CodingJobLifecycleContract.JobResponse.class);
        when(transitioned.stateVersion()).thenReturn(stateVersion + 1);
        when(transitioned.status()).thenReturn(CodingJobLifecycleContract.Status.RUNNING);
        when(lifecycle.transition(
                eq(jobId),
                eq(traceId),
                anyString(),
                any(byte[].class),
                any(CodingJobLifecycleContract.TransitionRequest.class)))
                .thenReturn(transitioned);
        CodingHandlerCommandService service = new CodingHandlerCommandService(
                jdbc,
                transactions,
                lifecycle,
                lifecycleService,
                lifecycleDigester,
                mapper,
                Clock.fixed(now, ZoneOffset.UTC));
        AuthenticatedActor actor =
                new AuthenticatedActor(actorId, "snapshot-admin", AdminRole.GENERAL_ADMIN);
        CodingHandlerContract.ApprovalDecisionRequest request =
                new CodingHandlerContract.ApprovalDecisionRequest(
                        "1.0",
                        traceId,
                        stateVersion,
                        pipelineAttempt,
                        approvalId,
                        "github_approval",
                        CodingHandlerContract.ApprovalStage.GITHUB,
                        stageRound,
                        candidate,
                        validation,
                        CodingHandlerContract.Decision.APPROVED,
                        null);

        CodingHandlerContract.ApprovalDecisionResponse response = service.decide(
                actor,
                jobId,
                "approval.snapshot-role",
                request);

        assertThat(response.approvalId()).isEqualTo(approvalId);
        assertThat(response.nodeId()).isEqualTo("github_approval");
        assertThat(response.stage()).isEqualTo(CodingHandlerContract.ApprovalStage.GITHUB);
        assertThat(response.actorRole()).isEqualTo("GENERAL_ADMIN");
    }

    @Test
    void enforcesTheSnapshotRequiredRoleWithoutBodyRoleInput() {
        for (String requiredRole : List.of("GENERAL_ADMIN", "SUPER_ADMIN")) {
            assertThatCode(() -> CodingHandlerCommandService.requireRole(
                    AdminRole.SUPER_ADMIN, requiredRole)).doesNotThrowAnyException();
        }
        assertThatCode(() -> CodingHandlerCommandService.requireRole(
                AdminRole.GENERAL_ADMIN, "GENERAL_ADMIN")).doesNotThrowAnyException();
        assertThatThrownBy(() -> CodingHandlerCommandService.requireRole(
                AdminRole.GENERAL_ADMIN, "SUPER_ADMIN"))
                .isInstanceOf(CodingJobLifecycleException.class);
        for (String requiredRole : List.of("GENERAL_ADMIN", "SUPER_ADMIN", "ARBITRARY")) {
            assertThatThrownBy(() -> CodingHandlerCommandService.requireRole(
                    AdminRole.GENERAL_USER, requiredRole))
                    .isInstanceOf(CodingJobLifecycleException.class);
        }
    }

    @Test
    void authoritativeCreateUsesOnlyTheAuthenticatedActor() {
        UUID actorId = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        AuthenticatedActor actor =
                new AuthenticatedActor(actorId, "admin", AdminRole.GENERAL_ADMIN);
        CodingHandlerContract.CreateCodingJobRequest request =
                new CodingHandlerContract.CreateCodingJobRequest(
                        "1.0",
                        UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
                        UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc"),
                        UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd"),
                        "start",
                        "sha1:1111111111111111111111111111111111111111",
                        "sha256:2222222222222222222222222222222222222222222222222222222222222222",
                        "sha256:3333333333333333333333333333333333333333333333333333333333333333",
                        "coding-v2",
                        List.of("TOOL_CALLING"),
                        List.of("start"),
                        Instant.parse("2026-08-31T00:00:00Z"),
                        "private natural-language request");

        CodingJobLifecycleContract.CreateRequest authoritative =
                CodingHandlerCommandService.authoritativeCreateRequest(actor, request);

        assertThat(authoritative.actorId()).isEqualTo(actorId);
        assertThat(authoritative.projectId()).isEqualTo(request.projectId());
        assertThat(authoritative.repositoryId()).isEqualTo(request.repositoryId());
    }
}

package org.urizo.axmodulestudio.backend.coding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.urizo.axmodulestudio.backend.coding.dto.CodingHandlerContract;

class CodingApprovalReadinessTest {

    private static final UUID JOB_ID =
            UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID TRACE_ID =
            UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    private static final String CANDIDATE =
            "sha1:1111111111111111111111111111111111111111";
    private static final String VALIDATION =
            "sha256:2222222222222222222222222222222222222222222222222222222222222222";
    private static final String DEPLOY_VALIDATION =
            "sha256:3333333333333333333333333333333333333333333333333333333333333333";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void resolvesTheSnapshotSelectedApprovalFromTheCurrentWorkerOutcome() {
        String nodeId = "snapshot_release_approval";
        int stageRound = 4;
        UUID approvalId = CodingApprovalId.forStage(
                JOB_ID,
                1,
                nodeId,
                CodingHandlerContract.ApprovalStage.GITHUB,
                stageRound);
        String outcome = outcome(
                approvalId, nodeId, "GITHUB", stageRound, "GENERAL_ADMIN").toString();

        assertThat(CodingApprovalReadiness.find(
                jdbc(List.of(outcome)), OBJECT_MAPPER, JOB_ID, TRACE_ID, 8, 1))
                .get()
                .isEqualTo(new CodingApprovalReadiness.ReadyApproval(
                        approvalId,
                        nodeId,
                        CodingHandlerContract.ApprovalStage.GITHUB,
                        stageRound,
                        "GENERAL_ADMIN",
                        CANDIDATE,
                        VALIDATION));
    }

    @Test
    void failsClosedForMissingMalformedOrAmbiguousCurrentOutcomes() {
        String nodeId = "scope_approval";
        UUID approvalId = CodingApprovalId.forStage(
                JOB_ID, 1, nodeId, CodingHandlerContract.ApprovalStage.SCOPE, 1);
        ObjectNode malformed = outcome(
                approvalId, nodeId, "SCOPE", 1, "GENERAL_ADMIN");
        malformed.withObject("pendingApproval").put("approvalId", 7);
        String valid = outcome(
                approvalId, nodeId, "SCOPE", 1, "GENERAL_ADMIN").toString();

        assertThat(CodingApprovalReadiness.find(
                jdbc(List.of()), OBJECT_MAPPER, JOB_ID, TRACE_ID, 8, 1)).isEmpty();
        assertThat(CodingApprovalReadiness.find(
                jdbc(List.of(malformed.toString())),
                OBJECT_MAPPER,
                JOB_ID,
                TRACE_ID,
                8,
                1)).isEmpty();
        assertThat(CodingApprovalReadiness.find(
                jdbc(List.of(valid, valid)), OBJECT_MAPPER, JOB_ID, TRACE_ID, 8, 1)).isEmpty();
    }

    @Test
    void bindsDeployApprovalToTheStableDeploymentRequestWhenPresent() {
        String nodeId = "deploy_approval";
        UUID approvalId = CodingApprovalId.forStage(
                JOB_ID, 1, nodeId, CodingHandlerContract.ApprovalStage.DEPLOY, 1);

        assertThat(CodingApprovalReadiness.find(
                jdbc(List.of(outcome(
                        approvalId, nodeId, "DEPLOY", 1, "GENERAL_ADMIN").toString()), true),
                OBJECT_MAPPER, JOB_ID, TRACE_ID, 8, 1))
                .get()
                .extracting(CodingApprovalReadiness.ReadyApproval::validationHash)
                .isEqualTo(DEPLOY_VALIDATION);
    }

    private static ObjectNode outcome(
            UUID approvalId,
            String nodeId,
            String stage,
            int stageRound,
            String requiredRole) {
        ObjectNode outcome = OBJECT_MAPPER.createObjectNode()
                .put("schemaVersion", "1.0")
                .put("jobId", JOB_ID.toString())
                .put("traceId", TRACE_ID.toString())
                .put("stateVersion", 8)
                .put("status", "WAITING_APPROVAL");
        outcome.putObject("pendingApproval")
                .put("approvalId", approvalId.toString())
                .put("nodeId", nodeId)
                .put("stage", stage)
                .put("stageRound", stageRound)
                .put("requiredRole", requiredRole);
        return outcome;
    }

    @SuppressWarnings("unchecked")
    private static JdbcTemplate jdbc(List<String> outcomes) {
        return jdbc(outcomes, false);
    }

    @SuppressWarnings("unchecked")
    private static JdbcTemplate jdbc(List<String> outcomes, boolean deploymentRequest) {
        return mock(JdbcTemplate.class, invocation -> {
            Object[] arguments = invocation.getArguments();
            if ("query".equals(invocation.getMethod().getName())
                    && arguments.length > 1
                    && arguments[0] instanceof String sql
                    && arguments[1] instanceof RowMapper<?> rowMapper) {
                if (sql.contains("FROM app.coding_worker_command")) {
                    List<Object> rows = new ArrayList<>();
                    for (String outcome : outcomes) {
                        ResultSet row = mock(ResultSet.class);
                        when(row.getString("response_json")).thenReturn(outcome);
                        rows.add(rowMapper.mapRow(row, rows.size()));
                    }
                    return rows;
                }
                if (sql.contains("handler_key = 'coding.preview'")
                        || sql.contains("handler_key = 'coding.pr_request'")) {
                    ResultSet row = mock(ResultSet.class);
                    when(row.getString("candidate_sha")).thenReturn(CANDIDATE);
                    when(row.getString("validation_hash")).thenReturn(VALIDATION);
                    return List.of(rowMapper.mapRow(row, 0));
                }
                if (deploymentRequest && sql.contains("handler_key = 'coding.deploy_request'")) {
                    ResultSet row = mock(ResultSet.class);
                    when(row.getString("candidate_sha")).thenReturn(CANDIDATE);
                    when(row.getString("validation_hash")).thenReturn(DEPLOY_VALIDATION);
                    return List.of(rowMapper.mapRow(row, 0));
                }
                if (sql.contains("handler_key = ?")) {
                    ResultSet row = mock(ResultSet.class);
                    when(row.getString("result_port")).thenReturn("feasible");
                    return List.of(rowMapper.mapRow(row, 0));
                }
                return List.of();
            }
            return Answers.RETURNS_DEFAULTS.answer(invocation);
        });
    }
}

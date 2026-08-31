package org.urizo.axmodulestudio.backend.coding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.urizo.axmodulestudio.backend.coding.dto.CodingHandlerContract;

class CodingHandlerResultServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void aggregateUsesTheLatestWorkerOutcomePendingApprovalInsteadOfTheFixedStageChain()
            throws Exception {
        UUID jobId = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        UUID traceId = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
        UUID workspaceId = UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
        UUID credentialId = UUID.fromString("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee");
        String nodeId = "snapshot_release_approval";
        int stageRound = 4;
        UUID approvalId = CodingApprovalId.forStage(
                jobId,
                1,
                nodeId,
                CodingHandlerContract.ApprovalStage.GITHUB,
                stageRound);
        String candidate = "sha1:1111111111111111111111111111111111111111";
        String validation =
                "sha256:2222222222222222222222222222222222222222222222222222222222222222";
        Instant now = Instant.parse("2026-08-31T12:00:00Z");
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode outcome = mapper.createObjectNode()
                .put("schemaVersion", "1.0")
                .put("jobId", jobId.toString())
                .put("traceId", traceId.toString())
                .put("stateVersion", 8)
                .put("status", "WAITING_APPROVAL");
        outcome.putObject("pendingApproval")
                .put("approvalId", approvalId.toString())
                .put("nodeId", nodeId)
                .put("stage", "GITHUB")
                .put("stageRound", stageRound)
                .put("requiredRole", "GENERAL_ADMIN");

        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });

        ResultSet credential = mock(ResultSet.class);
        when(credential.getObject("credential_id", UUID.class)).thenReturn(credentialId);
        when(jdbc.query(
                argThat(sql -> sql != null
                        && sql.contains("FROM app.coding_service_credential")),
                any(RowMapper.class),
                any(byte[].class),
                any(Timestamp.class),
                any(Timestamp.class))).thenAnswer(invocation -> {
                    RowMapper<Object> rowMapper = invocation.getArgument(1);
                    return List.of(rowMapper.mapRow(credential, 0));
                });

        ResultSet attempt = mock(ResultSet.class);
        when(attempt.getObject("trace_id", UUID.class)).thenReturn(traceId);
        when(attempt.getString("job_status")).thenReturn("WAITING_APPROVAL");
        when(attempt.getInt("state_version")).thenReturn(8);
        when(attempt.getObject("workspace_id", UUID.class)).thenReturn(workspaceId);
        when(attempt.getString("status")).thenReturn("ACTIVE");
        when(attempt.getString("request_text")).thenReturn("Use the stored Snapshot graph.");
        when(attempt.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
        when(jdbc.query(
                argThat(sql -> sql != null
                        && sql.contains("FROM app.coding_pipeline_attempt cpa")),
                any(RowMapper.class),
                eq(jobId),
                eq(1))).thenAnswer(invocation -> {
                    RowMapper<Object> rowMapper = invocation.getArgument(1);
                    return List.of(rowMapper.mapRow(attempt, 0));
                });

        ResultSet storedOutcome = mock(ResultSet.class);
        when(storedOutcome.getString("response_json")).thenReturn(outcome.toString());
        when(storedOutcome.getString(1)).thenReturn(outcome.toString());
        when(jdbc.query(
                argThat(sql -> sql != null && sql.contains("FROM app.coding_worker_command")),
                any(RowMapper.class),
                eq(jobId),
                eq(jobId.toString()),
                eq(traceId.toString()),
                eq("8"))).thenAnswer(invocation -> {
                    RowMapper<Object> rowMapper = invocation.getArgument(1);
                    return List.of(rowMapper.mapRow(storedOutcome, 0));
                });
        ResultSet subject = mock(ResultSet.class);
        when(subject.getString("candidate_sha")).thenReturn(candidate);
        when(subject.getString("validation_hash")).thenReturn(validation);
        when(jdbc.query(
                argThat(sql -> sql != null
                        && sql.contains("handler_key = 'coding.preview'")),
                any(RowMapper.class),
                eq(jobId),
                eq(1))).thenAnswer(invocation -> {
                    RowMapper<Object> rowMapper = invocation.getArgument(1);
                    return List.of(rowMapper.mapRow(subject, 0));
                });
        when(jdbc.query(
                argThat(sql -> sql != null
                        && sql.contains("handler_key = 'coding.pr_request'")),
                any(RowMapper.class),
                eq(jobId),
                eq(1))).thenAnswer(invocation -> {
                    RowMapper<Object> rowMapper = invocation.getArgument(1);
                    return List.of(rowMapper.mapRow(subject, 0));
                });

        CodingHandlerResultService service = new CodingHandlerResultService(
                jdbc,
                transactions,
                mapper,
                Clock.fixed(now, ZoneOffset.UTC));

        assertThat(service.aggregate("Bearer worker-token", jobId, 1).pendingApprovals())
                .containsExactly(new CodingHandlerContract.PendingApprovalSummary(
                        approvalId,
                        nodeId,
                        CodingHandlerContract.ApprovalStage.GITHUB,
                        stageRound,
                        "GENERAL_ADMIN"));
    }

    @Test
    void idempotencyDigestCanonicalizesPayloadObjectFieldOrder() {
        ObjectMapper mapper = new ObjectMapper();
        CodingHandlerResultService service =
                new CodingHandlerResultService(null, null, mapper, Clock.systemUTC());
        UUID jobId = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        UUID resultId = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
        UUID traceId = UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
        ObjectNode firstPayload = mapper.createObjectNode().put("alpha", 1).put("beta", 2);
        ObjectNode reorderedPayload = mapper.createObjectNode().put("beta", 2).put("alpha", 1);
        CodingHandlerContract.PutResultRequest first = request(traceId, firstPayload);
        CodingHandlerContract.PutResultRequest reordered = request(traceId, reorderedPayload);

        assertThat(service.requestDigest(jobId, 1, resultId, first))
                .containsExactly(service.requestDigest(jobId, 1, resultId, reordered));
    }

    @Test
    void idempotencyDigestIgnoresOnlyTheFirstWriteStateVersionPrecondition() {
        ObjectMapper mapper = new ObjectMapper();
        CodingHandlerResultService service =
                new CodingHandlerResultService(null, null, mapper, Clock.systemUTC());
        UUID jobId = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        UUID resultId = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
        UUID traceId = UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
        ObjectNode payload = mapper.createObjectNode().put("alpha", 1);
        CodingHandlerContract.PutResultRequest first = request(traceId, 2, payload);
        CodingHandlerContract.PutResultRequest reclaimed = request(traceId, 9, payload);
        CodingHandlerContract.PutResultRequest changed = new CodingHandlerContract.PutResultRequest(
                "1.0",
                traceId,
                9,
                "coding.analyze",
                CodingHandlerContract.ResultType.ANALYSIS,
                "infeasible",
                null,
                null,
                null,
                null,
                payload);

        assertThat(service.requestDigest(jobId, 1, resultId, first))
                .containsExactly(service.requestDigest(jobId, 1, resultId, reclaimed));
        assertThat(service.requestDigest(jobId, 1, resultId, first))
                .isNotEqualTo(service.requestDigest(jobId, 1, resultId, changed));
    }

    @Test
    void sideEffectResultsRequireTheLatestApprovedPreviewSubject() {
        String candidate = "sha1:1111111111111111111111111111111111111111";
        String validation =
                "sha256:2222222222222222222222222222222222222222222222222222222222222222";
        CodingHandlerResultService.ResultSubject subject =
                new CodingHandlerResultService.ResultSubject(candidate, validation);
        CodingHandlerResultService.ApprovalSubject approved =
                new CodingHandlerResultService.ApprovalSubject(
                        CodingHandlerContract.Decision.APPROVED, candidate, validation);

        CodingHandlerResultService.requireBoundarySubject(
                CodingHandlerContract.ResultType.PULL_REQUEST, subject, subject, approved);
        CodingHandlerResultService.requireBoundarySubject(
                CodingHandlerContract.ResultType.DEPLOY_REQUEST, subject, subject, approved);

        assertThatThrownBy(() -> CodingHandlerResultService.requireBoundarySubject(
                CodingHandlerContract.ResultType.PULL_REQUEST,
                subject,
                new CodingHandlerResultService.ResultSubject(
                        "sha1:3333333333333333333333333333333333333333", validation),
                approved))
                .isInstanceOf(CodingWorkerException.class)
                .hasMessageContaining("latest authorized candidate");
        assertThatThrownBy(() -> CodingHandlerResultService.requireBoundarySubject(
                CodingHandlerContract.ResultType.DEPLOY_REQUEST,
                subject,
                subject,
                new CodingHandlerResultService.ApprovalSubject(
                        CodingHandlerContract.Decision.APPROVED,
                        candidate,
                        "sha256:4444444444444444444444444444444444444444444444444444444444444444")))
                .isInstanceOf(CodingWorkerException.class)
                .hasMessageContaining("latest authorized candidate");
        assertThatThrownBy(() -> CodingHandlerResultService.requireBoundarySubject(
                CodingHandlerContract.ResultType.DEPLOY_REQUEST,
                subject,
                subject,
                null))
                .isInstanceOf(CodingWorkerException.class);
    }

    @Test
    void reviewAndPreviewRejectCandidateDrift() {
        String codeCandidate = "sha1:1111111111111111111111111111111111111111";
        String driftedCandidate = "sha1:3333333333333333333333333333333333333333";

        CodingHandlerResultService.requireCandidateChain(
                CodingHandlerContract.ResultType.REVIEW,
                codeCandidate,
                codeCandidate,
                null);
        assertThatThrownBy(() -> CodingHandlerResultService.requireCandidateChain(
                CodingHandlerContract.ResultType.REVIEW,
                driftedCandidate,
                codeCandidate,
                null))
                .isInstanceOf(CodingWorkerException.class);

        CodingHandlerResultService.requireCandidateChain(
                CodingHandlerContract.ResultType.DIFF,
                codeCandidate,
                codeCandidate,
                new CodingHandlerResultService.ReviewSubject("passed", codeCandidate));
        assertThatThrownBy(() -> CodingHandlerResultService.requireCandidateChain(
                CodingHandlerContract.ResultType.DIFF,
                driftedCandidate,
                codeCandidate,
                new CodingHandlerResultService.ReviewSubject("passed", driftedCandidate)))
                .isInstanceOf(CodingWorkerException.class);
        assertThatThrownBy(() -> CodingHandlerResultService.requireCandidateChain(
                CodingHandlerContract.ResultType.DIFF,
                codeCandidate,
                codeCandidate,
                new CodingHandlerResultService.ReviewSubject(
                        "changes_requested", codeCandidate)))
                .isInstanceOf(CodingWorkerException.class);
    }

    @Test
    void sameResultIdUsesOneStableDatabaseSerializationKey() {
        UUID resultId = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");

        assertThat(CodingHandlerResultService.resultLockKey(resultId))
                .isEqualTo("CODING_HANDLER_RESULT:" + resultId)
                .isEqualTo(CodingHandlerResultService.resultLockKey(resultId));
    }

    private static CodingHandlerContract.PutResultRequest request(
            UUID traceId, ObjectNode payload) {
        return request(traceId, 2, payload);
    }

    private static CodingHandlerContract.PutResultRequest request(
            UUID traceId, int expectedStateVersion, ObjectNode payload) {
        return new CodingHandlerContract.PutResultRequest(
                "1.0",
                traceId,
                expectedStateVersion,
                "coding.analyze",
                CodingHandlerContract.ResultType.ANALYSIS,
                "feasible",
                null,
                null,
                null,
                null,
                payload);
    }
}

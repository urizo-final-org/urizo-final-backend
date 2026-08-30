package org.urizo.axmodulestudio.backend.coding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.urizo.axmodulestudio.backend.coding.dto.CodingHandlerContract;

class CodingHandlerResultServiceTest {

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

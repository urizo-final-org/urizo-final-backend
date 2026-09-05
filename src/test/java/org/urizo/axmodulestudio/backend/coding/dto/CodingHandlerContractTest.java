package org.urizo.axmodulestudio.backend.coding.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class CodingHandlerContractTest {

    private static final UUID TRACE_ID =
            UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final String CANDIDATE =
            "sha1:1111111111111111111111111111111111111111";
    private static final String DIGEST =
            "sha256:2222222222222222222222222222222222222222222222222222222222222222";

    @Test
    void acceptsOnlyRegisteredHandlerTypePortTuples() {
        CodingHandlerContract.PutResultRequest valid = new CodingHandlerContract.PutResultRequest(
                "1.0", TRACE_ID, 2, "coding.preview",
                CodingHandlerContract.ResultType.DIFF, "ready", UUID.randomUUID(),
                CANDIDATE, DIGEST, DIGEST, JsonNodeFactory.instance.objectNode());
        assertThat(valid.resultPort()).isEqualTo("ready");

        assertThatThrownBy(() -> new CodingHandlerContract.PutResultRequest(
                "1.0", TRACE_ID, 2, "coding.preview",
                CodingHandlerContract.ResultType.DIFF, "completed", UUID.randomUUID(),
                CANDIDATE, DIGEST, DIGEST, JsonNodeFactory.instance.objectNode()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("registered combination");
        assertThatThrownBy(() -> new CodingHandlerContract.PutResultRequest(
                "1.0", TRACE_ID, 2, "coding.preview",
                CodingHandlerContract.ResultType.DIFF, "ready", UUID.randomUUID(),
                CANDIDATE, DIGEST, null, JsonNodeFactory.instance.objectNode()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("validationHash");
    }

    @Test
    void registersPrMergeAndDeploymentCompletionContracts() {
        assertThat(new CodingHandlerContract.StageExecutionResponse(
                "1.0", UUID.randomUUID(), "coding.pr_complete", "completed",
                UUID.randomUUID(), CANDIDATE, DIGEST, DIGEST,
                JsonNodeFactory.instance.objectNode()).resultPort()).isEqualTo("completed");
        assertThat(new CodingHandlerContract.StageExecutionResponse(
                "1.0", UUID.randomUUID(), "coding.dev_merge_check", "not_merged",
                UUID.randomUUID(), CANDIDATE, DIGEST, DIGEST,
                JsonNodeFactory.instance.objectNode()).resultPort()).isEqualTo("not_merged");
        assertThat(new CodingHandlerContract.StageExecutionResponse(
                "1.0", UUID.randomUUID(), "coding.deploy", "blocked",
                UUID.randomUUID(), CANDIDATE, DIGEST, DIGEST,
                JsonNodeFactory.instance.objectNode()).resultPort()).isEqualTo("blocked");
    }

    @Test
    void requiresPostPreviewApprovalsToCarryCandidateEvidence() {
        assertThatThrownBy(() -> new CodingHandlerContract.ApprovalDecisionRequest(
                "1.0", TRACE_ID, 3, 1, UUID.randomUUID(), "cms_approval",
                CodingHandlerContract.ApprovalStage.CMS, 1,
                null, null, CodingHandlerContract.Decision.APPROVED, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("candidateSha");
    }

    @Test
    void acceptsTheSnapshotSelectedApprovalNodeAndRound() {
        CodingHandlerContract.ApprovalDecisionRequest request =
                new CodingHandlerContract.ApprovalDecisionRequest(
                        "1.0",
                        TRACE_ID,
                        3,
                        1,
                        UUID.randomUUID(),
                        "snapshot-release_approval",
                        CodingHandlerContract.ApprovalStage.GITHUB,
                        4,
                        CANDIDATE,
                        DIGEST,
                        CodingHandlerContract.Decision.APPROVED,
                        null);

        assertThat(request.nodeId()).isEqualTo("snapshot-release_approval");
        assertThat(request.stageRound()).isEqualTo(4);
    }

    @Test
    void stageExecutionUsesOnlyExistingAi04HandlersAndResultPorts() {
        UUID resultId = UUID.randomUUID();
        CodingHandlerContract.StageExecutionRequest request =
                new CodingHandlerContract.StageExecutionRequest(
                        "1.0", TRACE_ID, 3, 1, "coding.code", resultId);
        CodingHandlerContract.StageExecutionResponse response =
                new CodingHandlerContract.StageExecutionResponse(
                        "1.0", resultId, "coding.code", "completed",
                        UUID.randomUUID(), CANDIDATE, DIGEST, null,
                        JsonNodeFactory.instance.objectNode());

        assertThat(request.handlerKey()).isEqualTo("coding.code");
        assertThat(response.diffDigest()).isEqualTo(DIGEST);
        assertThatThrownBy(() -> new CodingHandlerContract.StageExecutionRequest(
                "1.0", TRACE_ID, 3, 1, "coding.dynamic", resultId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AI04");
    }

    @Test
    void redactsNaturalLanguageRequestsFromStringRepresentations() {
        CodingHandlerContract.InitializeRequest request =
                new CodingHandlerContract.InitializeRequest(
                        "1.0", TRACE_ID, "sensitive implementation request");
        assertThat(request.toString())
                .contains("requestText=REDACTED")
                .doesNotContain("sensitive implementation request");
    }

    @Test
    void rejectsClientSuppliedActorAndWorkIdentities() {
        ObjectMapper mapper = new ObjectMapper()
                .findAndRegisterModules()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        String create = """
                {
                  "schemaVersion":"1.0",
                  "profileVersionId":"11111111-1111-4111-8111-111111111111",
                  "actorId":"22222222-2222-4222-8222-222222222222",
                  "projectId":"33333333-3333-4333-8333-333333333333",
                  "repositoryId":"44444444-4444-4444-8444-444444444444",
                  "graphStep":"start",
                  "baseSha":"sha1:1111111111111111111111111111111111111111",
                  "contextDigest":"sha256:2222222222222222222222222222222222222222222222222222222222222222",
                  "policyHash":"sha256:3333333333333333333333333333333333333333333333333333333333333333",
                  "promptVersion":"coding-v1",
                  "allowedCapabilities":["CHAT"],
                  "allowedNodes":["start"],
                  "expiresAt":"2026-08-31T00:00:00Z",
                  "requestText":"implement safely"
                }
                """;
        String initialize = """
                {
                  "schemaVersion":"1.0",
                  "traceId":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                  "requestText":"implement safely",
                  "systemWorkId":"SYSTEM-LLMOPS-SPOOF"
                }
                """;

        assertThatThrownBy(() -> mapper.readValue(
                create, CodingHandlerContract.CreateCodingJobRequest.class))
                .hasMessageContaining("actorId");
        assertThatThrownBy(() -> mapper.readValue(
                initialize, CodingHandlerContract.InitializeRequest.class))
                .hasMessageContaining("systemWorkId");
    }
}

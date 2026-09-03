package org.urizo.axmodulestudio.backend.orchestration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class ProfileSnapshotValidatorTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void acceptsTheRegisteredSnapshotContractsForBothProfiles() throws Exception {
        JsonNode llmOps = authoringSnapshot();
        JsonNode naturalCms = authoringSnapshot(
                "contracts/fixtures/orchestration/natural-cms-handler.snapshot.valid.json");
        JsonNode contractFixture = authoringSnapshot(
                "contracts/fixtures/orchestration/profile-version.snapshot.valid.json");

        assertAll(
                () -> assertThatCode(() ->
                        ProfileSnapshotValidator.validateAuthoring("LLM_OPS", llmOps))
                        .doesNotThrowAnyException(),
                () -> assertThatCode(() ->
                        ProfileSnapshotValidator.validateAuthoring("NATURAL_CMS", naturalCms))
                        .doesNotThrowAnyException(),
                () -> assertThatCode(() ->
                        ProfileSnapshotValidator.validateAuthoring("LLM_OPS", contractFixture))
                        .doesNotThrowAnyException());
    }

    @Test
    void acceptsTheV4LlmOpsPrToDeployTail() throws Exception {
        ObjectNode snapshot = fullSnapshot();

        assertAll(
                () -> assertThat(snapshot.path("profileVersion").intValue()).isEqualTo(4),
                () -> assertThat(snapshot.withArray("nodes").size()).isEqualTo(17),
                () -> assertThat(hasNode(snapshot, "cms_approval")).isFalse(),
                () -> assertThat(node(snapshot, "pr_complete").path("handlerKey").textValue())
                        .isEqualTo("coding.pr_complete"),
                () -> assertThat(node(snapshot, "dev_merge_check").path("resultPorts").toString())
                        .isEqualTo("[\"merged\",\"not_merged\",\"blocked\"]"),
                () -> assertThat(edge(snapshot, "github_approval", "approved").path("to").textValue())
                        .isEqualTo("pr_complete"),
                () -> assertThat(edge(snapshot, "dev_merge_check", "not_merged").path("to").textValue())
                        .isEqualTo("deploy_request"),
                () -> assertThatCode(() -> ProfileSnapshotValidator.validateStored(
                        UUID.fromString(snapshot.path("profileVersionId").textValue()),
                        "LLM_OPS", 4, snapshot)).doesNotThrowAnyException());
    }

    @Test
    void rejectsUnlockedOrRemovedGuardrails() throws Exception {
        ObjectNode unlocked = (ObjectNode) authoringSnapshot();
        unlocked.withArray("nodes").get(1).withObject("config").put("locked", false);

        assertValidationFailure(() ->
                ProfileSnapshotValidator.validateAuthoring("LLM_OPS", unlocked));

        ObjectNode removed = (ObjectNode) authoringSnapshot();
        removed.withArray("nodes").remove(1);
        removed.withArray("edges").removeAll();

        assertValidationFailure(() ->
                ProfileSnapshotValidator.validateAuthoring("LLM_OPS", removed));
    }

    @Test
    void rejectsUnknownHandlersAndGuardrailBypassPaths() throws Exception {
        ObjectNode unknown = (ObjectNode) authoringSnapshot();
        ((ObjectNode) unknown.withArray("nodes").get(2)).put("handlerKey", "custom.execute");

        assertValidationFailure(() ->
                ProfileSnapshotValidator.validateAuthoring("LLM_OPS", unknown));

        ObjectNode bypass = (ObjectNode) authoringSnapshot();
        ((ObjectNode) bypass.withArray("nodes").get(0))
                .withArray("resultPorts").add("skip");
        bypass.withArray("edges").addObject()
                .put("from", "start")
                .put("resultPort", "skip")
                .put("to", "end");

        assertValidationFailure(() ->
                ProfileSnapshotValidator.validateAuthoring("LLM_OPS", bypass));
    }

    @Test
    void rejectsExecutionContextAndStoredIdentityChanges() throws Exception {
        ObjectNode authoring = (ObjectNode) authoringSnapshot();
        authoring.withObject("toolPolicy").put("jobId", "not-profile-data");

        assertValidationFailure(() ->
                ProfileSnapshotValidator.validateAuthoring("LLM_OPS", authoring));

        ObjectNode stored = fullSnapshot();
        assertValidationFailure(() -> ProfileSnapshotValidator.validateStored(
                UUID.fromString("99999999-9999-4999-8999-999999999999"),
                "LLM_OPS",
                stored.path("profileVersion").intValue(),
                stored));
    }

    @Test
    void rejectsMalformedModelBindingsAndUnregisteredTools() throws Exception {
        ObjectNode malformedBinding = (ObjectNode) authoringSnapshot();
        malformedBinding.withObject("modelBindings").put("analyze", "arbitrary-model");

        assertValidationFailure(() ->
                ProfileSnapshotValidator.validateAuthoring("LLM_OPS", malformedBinding));

        ObjectNode unknownTool = (ObjectNode) authoringSnapshot();
        unknownTool.withObject("toolPolicy").withArray("allowedTools").add("shell_anything");

        assertValidationFailure(() ->
                ProfileSnapshotValidator.validateAuthoring("LLM_OPS", unknownTool));

        ObjectNode missingAllowedTools = (ObjectNode) authoringSnapshot();
        missingAllowedTools.withObject("toolPolicy").remove("allowedTools");

        assertValidationFailure(() ->
                ProfileSnapshotValidator.validateAuthoring("LLM_OPS", missingAllowedTools));
    }

    @Test
    void acceptsOnlyTheAttemptCountCompiledByThePythonRuntime() throws Exception {
        ObjectNode supported = (ObjectNode) authoringSnapshot();
        supported.withObject("config").put("maxAttempts", 3);

        assertThatCode(() -> ProfileSnapshotValidator.validateAuthoring(
                "LLM_OPS", supported)).doesNotThrowAnyException();

        for (int maxAttempts : List.of(0, 1, 2, 4, 20, 21)) {
            ObjectNode authoring = (ObjectNode) authoringSnapshot();
            authoring.withObject("config").put("maxAttempts", maxAttempts);

            assertValidationFailure(() -> ProfileSnapshotValidator.validateAuthoring(
                    "LLM_OPS", authoring));
        }
    }

    @Test
    void rejectsUnregisteredModelBindingsBeforeActivation() throws Exception {
        ObjectNode unknownPrimary = (ObjectNode) authoringSnapshot();
        unknownPrimary.withObject("modelBindings").withObject("analyze")
                .put("primary", "unregistered-binding");

        ObjectNode unknownFallback = (ObjectNode) authoringSnapshot();
        unknownFallback.withObject("modelBindings").withObject("analyze")
                .withArray("fallback").add("unregistered-binding");

        ObjectNode otherProfileBinding = (ObjectNode) authoringSnapshot();
        otherProfileBinding.withObject("modelBindings").withObject("analyze")
                .put("primary", "natural-cms-analyze");

        assertAll(
                () -> assertValidationFailure(() ->
                        ProfileSnapshotValidator.validateAuthoring(
                                "LLM_OPS", unknownPrimary)),
                () -> assertValidationFailure(() ->
                        ProfileSnapshotValidator.validateAuthoring(
                                "LLM_OPS", unknownFallback)),
                () -> assertValidationFailure(() ->
                        ProfileSnapshotValidator.validateAuthoring(
                                "LLM_OPS", otherProfileBinding)));
    }

    @Test
    void enforcesThePythonCommonHandlerConfigContract() throws Exception {
        ObjectNode startConfig = (ObjectNode) authoringSnapshot();
        node(startConfig, "start").withObject("config").put("unexpected", true);

        ObjectNode checkConfig = commonCheckSnapshot();
        node(checkConfig, "rework_gate").withObject("config").put("unexpected", true);

        ObjectNode endConfig = (ObjectNode) authoringSnapshot();
        node(endConfig, "end").withObject("config").put("unexpected", true);

        assertAll(
                () -> assertValidationFailure(() ->
                        ProfileSnapshotValidator.validateAuthoring("LLM_OPS", startConfig)),
                () -> assertValidationFailure(() ->
                        ProfileSnapshotValidator.validateAuthoring("LLM_OPS", checkConfig)),
                () -> assertValidationFailure(() ->
                        ProfileSnapshotValidator.validateAuthoring("LLM_OPS", endConfig)));
    }

    @Test
    void enforcesThePythonFeatureHandlerConfigContract() throws Exception {
        ObjectNode codingEmptyConfig = (ObjectNode) authoringSnapshot();
        node(codingEmptyConfig, "analyze").withObject("config").put("unexpected", true);

        ObjectNode deployMode = (ObjectNode) authoringSnapshot();
        node(deployMode, "deploy_request").withObject("config").put("mode", "execute");

        ObjectNode approvalStage = (ObjectNode) authoringSnapshot();
        node(approvalStage, "scope_approval").withObject("config")
                .put("stage", "CANDIDATE");

        ObjectNode approvalRole = (ObjectNode) authoringSnapshot();
        node(approvalRole, "scope_approval").withObject("config")
                .put("requiredRole", "OWNER");

        ObjectNode previewApprovalStage = (ObjectNode) authoringSnapshot();
        node(previewApprovalStage, "preview_approval").withObject("config")
                .put("stage", "SCOPE");

        ObjectNode reworkMaximum = (ObjectNode) authoringSnapshot();
        node(reworkMaximum, "rework_gate").withObject("config")
                .put("maxReworkRounds", 0);

        ObjectNode cmsEmptyConfig = (ObjectNode) authoringSnapshot(
                "contracts/fixtures/orchestration/natural-cms-handler.snapshot.valid.json");
        node(cmsEmptyConfig, "analyze").withObject("config").put("unexpected", true);

        ObjectNode cmsApproval = (ObjectNode) authoringSnapshot(
                "contracts/fixtures/orchestration/natural-cms-handler.snapshot.valid.json");
        node(cmsApproval, "approval").withObject("config")
                .put("requiredRole", "SUPER_ADMIN");

        assertAll(
                () -> assertValidationFailure(() ->
                        ProfileSnapshotValidator.validateAuthoring(
                                "LLM_OPS", codingEmptyConfig)),
                () -> assertValidationFailure(() ->
                        ProfileSnapshotValidator.validateAuthoring("LLM_OPS", deployMode)),
                () -> assertValidationFailure(() ->
                        ProfileSnapshotValidator.validateAuthoring("LLM_OPS", approvalStage)),
                () -> assertValidationFailure(() ->
                        ProfileSnapshotValidator.validateAuthoring("LLM_OPS", approvalRole)),
                () -> assertValidationFailure(() ->
                        ProfileSnapshotValidator.validateAuthoring(
                                "LLM_OPS", previewApprovalStage)),
                () -> assertValidationFailure(() ->
                        ProfileSnapshotValidator.validateAuthoring("LLM_OPS", reworkMaximum)),
                () -> assertValidationFailure(() ->
                        ProfileSnapshotValidator.validateAuthoring(
                                "NATURAL_CMS", cmsEmptyConfig)),
                () -> assertValidationFailure(() ->
                        ProfileSnapshotValidator.validateAuthoring(
                                "NATURAL_CMS", cmsApproval)));
    }

    @Test
    void requiresCommonFailurePortsToTerminateAtEnd() throws Exception {
        ObjectNode guardrailFailureContinues = (ObjectNode) authoringSnapshot();
        edge(guardrailFailureContinues, "guardrail", "failed").put("to", "analyze");

        ObjectNode checkFailureContinues = commonCheckSnapshot();
        edge(checkFailureContinues, "rework_gate", "failed").put("to", "preview");

        assertAll(
                () -> assertValidationFailure(() ->
                        ProfileSnapshotValidator.validateAuthoring(
                                "LLM_OPS", guardrailFailureContinues)),
                () -> assertValidationFailure(() ->
                        ProfileSnapshotValidator.validateAuthoring(
                                "LLM_OPS", checkFailureContinues)));
    }

    @Test
    void rejectsCommonApprovalForDraftAndStoredSnapshots() throws Exception {
        ObjectNode draft = (ObjectNode) authoringSnapshot();
        useCommonApproval(draft);

        ObjectNode stored = fullSnapshot();
        useCommonApproval(stored);

        assertAll(
                () -> assertValidationFailure(() ->
                        ProfileSnapshotValidator.validateAuthoring("LLM_OPS", draft)),
                () -> assertValidationFailure(() -> ProfileSnapshotValidator.validateStored(
                        UUID.fromString(stored.path("profileVersionId").textValue()),
                        stored.path("profileKey").textValue(),
                        stored.path("profileVersion").intValue(),
                        stored)));
    }

    @Test
    void rejectsGuardrailProfilesTheBackendCannotEnforce() throws Exception {
        ObjectNode authoring = (ObjectNode) authoringSnapshot();
        authoring.put("guardrailProfileKey", "central.unregistered");

        assertThatThrownBy(() -> ProfileSnapshotValidator.validateAuthoring(
                "LLM_OPS", authoring))
                .isInstanceOfSatisfying(ProfileVersionException.class, failure -> {
                    assertThat(failure.code()).isEqualTo("CONTRACT_VALIDATION_FAILED");
                    assertThat(failure.getMessage()).contains("guardrailProfileKey");
                });
    }

    private static JsonNode authoringSnapshot() throws Exception {
        ObjectNode snapshot = fullSnapshot();
        removeStoredIdentity(snapshot);
        return snapshot;
    }

    private static JsonNode authoringSnapshot(String fixture) throws Exception {
        ObjectNode snapshot = (ObjectNode) OBJECT_MAPPER.readTree(
                Files.readString(Path.of(fixture)));
        removeStoredIdentity(snapshot);
        return snapshot;
    }

    private static void removeStoredIdentity(ObjectNode snapshot) {
        for (String field : List.of(
                "contractVersion", "profileVersionId", "profileKey", "profileVersion")) {
            snapshot.remove(field);
        }
    }

    private static ObjectNode fullSnapshot() throws Exception {
        return (ObjectNode) OBJECT_MAPPER.readTree(Files.readString(Path.of(
                "contracts/fixtures/orchestration/llm-ops-coding-handler.snapshot.valid.json")));
    }

    private static ObjectNode commonCheckSnapshot() throws Exception {
        ObjectNode snapshot = (ObjectNode) authoringSnapshot();
        ObjectNode check = node(snapshot, "rework_gate");
        check.put("handlerKey", "common.check");
        check.withArray("resultPorts").removeAll().add("passed").add("failed");
        check.withObject("config").removeAll();
        edge(snapshot, "rework_gate", "retry").put("resultPort", "passed");
        edge(snapshot, "rework_gate", "handover").put("resultPort", "failed");
        ((ObjectNode) snapshot.withObject("config").withArray("loopLimits").get(0))
                .put("resultPort", "passed");
        return snapshot;
    }

    private static void useCommonApproval(ObjectNode snapshot) {
        ObjectNode approval = node(snapshot, "scope_approval");
        approval.put("handlerKey", "common.approval");
        approval.withObject("config").removeAll();
    }

    private static ObjectNode node(ObjectNode snapshot, String id) {
        for (JsonNode candidate : snapshot.withArray("nodes")) {
            if (id.equals(candidate.path("id").textValue())) {
                return (ObjectNode) candidate;
            }
        }
        throw new AssertionError("missing node " + id);
    }

    private static boolean hasNode(ObjectNode snapshot, String id) {
        for (JsonNode candidate : snapshot.withArray("nodes")) {
            if (id.equals(candidate.path("id").textValue())) {
                return true;
            }
        }
        return false;
    }

    private static ObjectNode edge(ObjectNode snapshot, String from, String resultPort) {
        for (JsonNode candidate : snapshot.withArray("edges")) {
            if (from.equals(candidate.path("from").textValue())
                    && resultPort.equals(candidate.path("resultPort").textValue())) {
                return (ObjectNode) candidate;
            }
        }
        throw new AssertionError("missing edge " + from + "." + resultPort);
    }

    private static void assertValidationFailure(ThrowingRunnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ProfileVersionException.class, failure -> {
                    org.assertj.core.api.Assertions.assertThat(failure.code())
                            .isEqualTo("CONTRACT_VALIDATION_FAILED");
                    org.assertj.core.api.Assertions.assertThat(failure.status().value())
                            .isEqualTo(400);
                });
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}

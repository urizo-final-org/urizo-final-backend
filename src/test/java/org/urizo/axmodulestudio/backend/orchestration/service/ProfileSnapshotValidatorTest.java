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
        ObjectNode legacyContractFixture = (ObjectNode) OBJECT_MAPPER.readTree(
                Files.readString(Path.of(
                        "contracts/fixtures/orchestration/profile-version.snapshot.valid.json")));

        assertAll(
                () -> assertThatCode(() ->
                        ProfileSnapshotValidator.validateAuthoring("LLM_OPS", llmOps))
                        .doesNotThrowAnyException(),
                () -> assertThatCode(() ->
                        ProfileSnapshotValidator.validateAuthoring("NATURAL_CMS", naturalCms))
                        .doesNotThrowAnyException(),
                () -> assertThatCode(() -> ProfileSnapshotValidator.validateStored(
                        UUID.fromString(legacyContractFixture.path("profileVersionId").asText()),
                        "LLM_OPS", 1, legacyContractFixture))
                        .doesNotThrowAnyException());
    }

    @Test
    void requiresBindingsForNewDraftsAndActivationButReadsLegacyStoredSnapshots()
            throws Exception {
        ObjectNode legacy = fullSnapshot();
        legacy.remove("toolBindings");

        assertAll(
                () -> assertThatCode(() -> ProfileSnapshotValidator.validateStored(
                        UUID.fromString(legacy.path("profileVersionId").asText()),
                        "LLM_OPS", 4, legacy)).doesNotThrowAnyException(),
                () -> assertValidationFailure(() ->
                        ProfileSnapshotValidator.validateForActivation(
                                UUID.fromString(legacy.path("profileVersionId").asText()),
                                "LLM_OPS", 4, legacy)),
                () -> {
                    removeStoredIdentity(legacy);
                    assertValidationFailure(() ->
                            ProfileSnapshotValidator.validateAuthoring("LLM_OPS", legacy));
                });
    }

    @Test
    void rejectsUnknownOrIncompleteToolBindingsAndProfileWideMismatches() throws Exception {
        ObjectNode unknownNode = (ObjectNode) authoringSnapshot();
        ObjectNode unknownNodeBindings = unknownNode.withObject("toolBindings");
        unknownNodeBindings.set("unknown", unknownNodeBindings.remove("code"));

        ObjectNode unknownTool = (ObjectNode) authoringSnapshot();
        unknownTool.withObject("toolBindings").withObject("code")
                .put("shell_anything", "MODEL_OPTIONAL");

        ObjectNode unknownMode = (ObjectNode) authoringSnapshot();
        unknownMode.withObject("toolBindings").withObject("code")
                .put("read_file", "MODEL_AUTONOMOUS");

        ObjectNode globalMismatch = (ObjectNode) authoringSnapshot();
        removeArrayValue(globalMismatch.withObject("toolPolicy")
                .withArray("allowedTools"), "apply_patch");

        ObjectNode missingRequired = (ObjectNode) authoringSnapshot();
        missingRequired.withObject("toolBindings").withObject("preview")
                .remove("scan_changed_files");

        assertAll(
                () -> assertValidationFailure(() ->
                        ProfileSnapshotValidator.validateAuthoring("LLM_OPS", unknownNode)),
                () -> assertValidationFailure(() ->
                        ProfileSnapshotValidator.validateAuthoring("LLM_OPS", unknownTool)),
                () -> assertValidationFailure(() ->
                        ProfileSnapshotValidator.validateAuthoring("LLM_OPS", unknownMode)),
                () -> assertValidationFailure(() ->
                        ProfileSnapshotValidator.validateAuthoring("LLM_OPS", globalMismatch)),
                () -> assertValidationFailure(() ->
                        ProfileSnapshotValidator.validateAuthoring("LLM_OPS", missingRequired)));
    }

    @Test
    void acceptsOptionalBindingSubsetsWhileKeepingAllowedToolsAsTheUpperBound()
            throws Exception {
        ObjectNode stored = fullSnapshot();
        stored.withObject("toolBindings").withObject("code").remove("apply_patch");
        ObjectNode authoring = stored.deepCopy();
        removeStoredIdentity(authoring);

        assertAll(
                () -> assertThat(authoring.path("toolPolicy").path("allowedTools"))
                        .anyMatch(tool -> "apply_patch".equals(tool.asText())),
                () -> assertThatCode(() -> ProfileSnapshotValidator.validateAuthoring(
                        "LLM_OPS", authoring)).doesNotThrowAnyException(),
                () -> assertThatCode(() -> ProfileSnapshotValidator.validateForActivation(
                        UUID.fromString(stored.path("profileVersionId").asText()),
                        "LLM_OPS", stored.path("profileVersion").asInt(), stored))
                        .doesNotThrowAnyException());
    }

    @Test
    void rejectsRequiredStageBypassAndDangerousHandlerDuplicates() throws Exception {
        ObjectNode missingStage = (ObjectNode) authoringSnapshot();
        removeNode(missingStage, "pr_complete");
        edge(missingStage, "github_approval", "approved").put("to", "deploy_request");
        removeEdgesFrom(missingStage, "pr_complete");

        ObjectNode precedenceBypass = (ObjectNode) authoringSnapshot();
        edge(precedenceBypass, "scope_approval", "approved").put("to", "review");

        ObjectNode rejectedApprovalBypass = (ObjectNode) authoringSnapshot();
        edge(rejectedApprovalBypass, "preview_approval", "rejected")
                .put("to", "pr_request");
        rejectedApprovalBypass.withObject("config").withArray("loopLimits").remove(1);

        ObjectNode duplicateDeploy = (ObjectNode) authoringSnapshot();
        ObjectNode copied = node(duplicateDeploy, "deploy").deepCopy();
        copied.put("id", "deploy_copy");
        duplicateDeploy.withArray("nodes").add(copied);
        duplicateDeploy.withObject("config").put("maxNodes", 18);
        edge(duplicateDeploy, "dev_merge_check", "blocked").put("to", "deploy_copy");
        duplicateDeploy.withArray("edges").addObject()
                .put("from", "deploy_copy").put("resultPort", "completed").put("to", "end");
        duplicateDeploy.withArray("edges").addObject()
                .put("from", "deploy_copy").put("resultPort", "blocked").put("to", "end");

        assertAll(
                () -> assertValidationFailure(() -> ProfileSnapshotValidator
                        .validateAuthoring("LLM_OPS", missingStage)),
                () -> assertValidationFailure(() -> ProfileSnapshotValidator
                        .validateAuthoring("LLM_OPS", precedenceBypass)),
                () -> assertValidationFailure(() -> ProfileSnapshotValidator
                        .validateAuthoring("LLM_OPS", rejectedApprovalBypass)),
                () -> assertValidationFailure(() -> ProfileSnapshotValidator
                        .validateAuthoring("LLM_OPS", duplicateDeploy)));
    }

    @Test
    void enforcesLockedNaturalCmsBindingsAndApprovalBranches() throws Exception {
        ObjectNode wrongModelMode = (ObjectNode) authoringSnapshot(
                "contracts/fixtures/orchestration/natural-cms-handler.snapshot.valid.json");
        wrongModelMode.withObject("toolBindings").withObject("preview")
                .put("validate_cms_command", "MODEL_OPTIONAL");

        ObjectNode swappedApproval = (ObjectNode) authoringSnapshot(
                "contracts/fixtures/orchestration/natural-cms-handler.snapshot.valid.json");
        edge(swappedApproval, "approval", "approved").put("to", "discard");
        edge(swappedApproval, "approval", "rejected").put("to", "apply");

        assertAll(
                () -> assertValidationFailure(() -> ProfileSnapshotValidator
                        .validateAuthoring("NATURAL_CMS", wrongModelMode)),
                () -> assertValidationFailure(() -> ProfileSnapshotValidator
                        .validateAuthoring("NATURAL_CMS", swappedApproval)));
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
    void acceptsLegacyAliasesAndRequiresAWellFormedSelectionObjectForCatalogIds() throws Exception {
        ObjectNode legacy = (ObjectNode) authoringSnapshot();
        ObjectNode selected = (ObjectNode) authoringSnapshot();
        ObjectNode binding = selected.withObject("modelBindings").withObject("analyze");
        binding.put("primary", "openai-gpt-5-6-terra");
        binding.withObject("selections").withObject("openai-gpt-5-6-terra")
                .put("provider", "OPENAI")
                .put("model", "gpt-5.6-terra")
                .withObject("inference").put("reasoningIntensity", "HIGH");

        assertAll(
                () -> assertThatCode(() -> ProfileSnapshotValidator.validateAuthoring(
                        "LLM_OPS", legacy)).doesNotThrowAnyException(),
                () -> assertThatCode(() -> ProfileSnapshotValidator.validateAuthoring(
                        "LLM_OPS", selected)).doesNotThrowAnyException());

        binding.withObject("selections").remove("openai-gpt-5-6-terra");
        assertValidationFailure(() -> ProfileSnapshotValidator.validateAuthoring(
                "LLM_OPS", selected));
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

    private static void removeArrayValue(
            com.fasterxml.jackson.databind.node.ArrayNode values, String value) {
        for (int index = values.size() - 1; index >= 0; index--) {
            if (value.equals(values.get(index).asText())) values.remove(index);
        }
    }

    private static void removeNode(ObjectNode snapshot, String id) {
        com.fasterxml.jackson.databind.node.ArrayNode nodes = snapshot.withArray("nodes");
        for (int index = nodes.size() - 1; index >= 0; index--) {
            if (id.equals(nodes.get(index).path("id").asText())) nodes.remove(index);
        }
    }

    private static void removeEdgesFrom(ObjectNode snapshot, String nodeId) {
        com.fasterxml.jackson.databind.node.ArrayNode edges = snapshot.withArray("edges");
        for (int index = edges.size() - 1; index >= 0; index--) {
            if (nodeId.equals(edges.get(index).path("from").asText())) edges.remove(index);
        }
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

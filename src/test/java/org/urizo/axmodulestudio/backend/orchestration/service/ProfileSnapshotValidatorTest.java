package org.urizo.axmodulestudio.backend.orchestration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void acceptsTheExistingRegisteredSnapshotContract() throws Exception {
        JsonNode authoring = authoringSnapshot();

        assertThatCode(() -> ProfileSnapshotValidator.validateAuthoring("LLM_OPS", authoring))
                .doesNotThrowAnyException();
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
    void rejectsAttemptCountsTheCurrentDatabaseContractCannotPersist() throws Exception {
        ObjectNode authoring = (ObjectNode) authoringSnapshot();
        authoring.withObject("config").put("maxAttempts", 2);

        assertThatThrownBy(() -> ProfileSnapshotValidator.validateAuthoring(
                "LLM_OPS", authoring))
                .isInstanceOfSatisfying(ProfileVersionException.class, failure -> {
                    assertThat(failure.code()).isEqualTo("CONTRACT_VALIDATION_FAILED");
                    assertThat(failure.getMessage()).contains("maxAttempts must be 3");
                });
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
        for (String field : List.of(
                "contractVersion", "profileVersionId", "profileKey", "profileVersion")) {
            snapshot.remove(field);
        }
        return snapshot;
    }

    private static ObjectNode fullSnapshot() throws Exception {
        return (ObjectNode) OBJECT_MAPPER.readTree(Files.readString(Path.of(
                "contracts/fixtures/orchestration/llm-ops-coding-handler.snapshot.valid.json")));
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

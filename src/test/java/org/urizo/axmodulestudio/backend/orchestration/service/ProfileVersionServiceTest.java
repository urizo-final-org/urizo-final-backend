package org.urizo.axmodulestudio.backend.orchestration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.urizo.axmodulestudio.backend.orchestration.repository.ProfileVersionRepository;

class ProfileVersionServiceTest {

    private static final UUID PROFILE_VERSION_ID =
            UUID.fromString("77777777-7777-4777-8777-777777777777");
    private static final String AUTHORIZATION = "Bearer test-token";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ProfileVersionRepository repository = mock(ProfileVersionRepository.class);
    private final ProfileVersionService service = new ProfileVersionService(repository);

    @Test
    void returnsActiveSnapshotsForBoundJobs() {
        JsonNode snapshot = JsonNodeFactory.instance.objectNode().put("contractVersion", "1.0");
        when(repository.findById(AUTHORIZATION, PROFILE_VERSION_ID)).thenReturn(Optional.of(
                new ProfileVersionRepository.StoredProfileVersion("ACTIVE", snapshot)));

        JsonNode returned = service.getBound(AUTHORIZATION, PROFILE_VERSION_ID);

        assertThat(returned).isEqualTo(snapshot);
        assertThat(returned).isNotSameAs(snapshot);
    }

    @Test
    void returnsInactiveSnapshotsAlreadyBoundToRunningJobs() {
        JsonNode snapshot = JsonNodeFactory.instance.objectNode().put("contractVersion", "1.0");
        when(repository.findById(AUTHORIZATION, PROFILE_VERSION_ID)).thenReturn(Optional.of(
                new ProfileVersionRepository.StoredProfileVersion("INACTIVE", snapshot)));

        assertThat(service.getBound(AUTHORIZATION, PROFILE_VERSION_ID)).isEqualTo(snapshot);
    }

    @Test
    void reportsMissingVersionsWithoutConflatingThemWithInactiveVersions() {
        when(repository.findById(AUTHORIZATION, PROFILE_VERSION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getBound(AUTHORIZATION, PROFILE_VERSION_ID))
                .isInstanceOfSatisfying(ProfileVersionException.class, failure -> {
                    assertThat(failure.code()).isEqualTo("PROFILE_VERSION_NOT_FOUND");
                    assertThat(failure.status().value()).isEqualTo(404);
                    assertThat(failure.retryable()).isFalse();
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"DRAFT", "UNKNOWN"})
    void rejectsVersionsThatWereNeverExecutable(String status) {
        JsonNode snapshot = JsonNodeFactory.instance.objectNode().put("contractVersion", "1.0");
        when(repository.findById(AUTHORIZATION, PROFILE_VERSION_ID)).thenReturn(Optional.of(
                new ProfileVersionRepository.StoredProfileVersion(status, snapshot)));

        assertThatThrownBy(() -> service.getBound(AUTHORIZATION, PROFILE_VERSION_ID))
                .isInstanceOfSatisfying(ProfileVersionException.class, failure -> {
                    assertThat(failure.code()).isEqualTo("PROFILE_VERSION_NOT_ACTIVE");
                    assertThat(failure.status().value()).isEqualTo(409);
                    assertThat(failure.retryable()).isFalse();
                });
    }

    @Test
    void createsANewImmutableDraftFromTheExistingAuthoringContract() throws Exception {
        JsonNode authoring = authoringSnapshot();
        when(repository.createDraft(
                org.mockito.ArgumentMatchers.eq("LLM_OPS"),
                org.mockito.ArgumentMatchers.any(UUID.class),
                org.mockito.ArgumentMatchers.eq(authoring))).thenAnswer(invocation -> {
                    UUID id = invocation.getArgument(1);
                    return adminVersion(id, "LLM_OPS", 3, "DRAFT", fullSnapshot(id, 3, authoring));
                });

        ProfileVersionRepository.AdminStoredProfileVersion created =
                service.createDraft("LLM_OPS", authoring);

        assertThat(created.profileVersion()).isEqualTo(3);
        assertThat(created.status()).isEqualTo("DRAFT");
        assertThat(created.snapshot().path("profileVersionId").asText())
                .isEqualTo(created.profileVersionId().toString());
        assertThat(authoring.has("profileVersionId")).isFalse();
    }

    @Test
    void rejectsInvalidDraftsBeforeWriting() throws Exception {
        ObjectNode authoring = (ObjectNode) authoringSnapshot();
        authoring.withArray("nodes").get(1).withObject("config").put("locked", false);

        assertThatThrownBy(() -> service.createDraft("LLM_OPS", authoring))
                .isInstanceOfSatisfying(ProfileVersionException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("CONTRACT_VALIDATION_FAILED"));
        verify(repository, never()).createDraft(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void activatesOnlyValidatedDraftsAndKeepsInactiveVersionsTerminal() throws Exception {
        JsonNode authoring = authoringSnapshot();
        ProfileVersionRepository.AdminStoredProfileVersion draft = adminVersion(
                PROFILE_VERSION_ID,
                "LLM_OPS",
                2,
                "DRAFT",
                fullSnapshot(PROFILE_VERSION_ID, 2, authoring));
        ProfileVersionRepository.AdminStoredProfileVersion active = adminVersion(
                PROFILE_VERSION_ID,
                "LLM_OPS",
                2,
                "ACTIVE",
                draft.snapshot());
        when(repository.findAdminById(PROFILE_VERSION_ID)).thenReturn(Optional.of(draft));
        when(repository.activate(PROFILE_VERSION_ID)).thenReturn(Optional.of(active));

        assertThat(service.activate(PROFILE_VERSION_ID).status()).isEqualTo("ACTIVE");

        when(repository.findAdminById(PROFILE_VERSION_ID)).thenReturn(Optional.of(adminVersion(
                PROFILE_VERSION_ID, "LLM_OPS", 2, "INACTIVE", draft.snapshot())));
        assertThatThrownBy(() -> service.activate(PROFILE_VERSION_ID))
                .isInstanceOfSatisfying(ProfileVersionException.class, failure -> {
                    assertThat(failure.code()).isEqualTo("PROFILE_VERSION_CONFLICT");
                    assertThat(failure.status().value()).isEqualTo(409);
                });
    }

    @Test
    void listsOnlySupportedProfileKeys() {
        when(repository.findAll("NATURAL_CMS")).thenReturn(List.of());

        assertThat(service.listAdmin("NATURAL_CMS")).isEmpty();
        assertThatThrownBy(() -> service.listAdmin("UNKNOWN"))
                .isInstanceOf(ProfileVersionException.class);
    }

    private static ProfileVersionRepository.AdminStoredProfileVersion adminVersion(
            UUID id, String key, int version, String status, JsonNode snapshot) {
        return new ProfileVersionRepository.AdminStoredProfileVersion(
                id, key, version, status, Instant.parse("2026-08-31T00:00:00Z"), snapshot);
    }

    private static JsonNode authoringSnapshot() throws Exception {
        ObjectNode snapshot = (ObjectNode) OBJECT_MAPPER.readTree(Files.readString(Path.of(
                "contracts/fixtures/orchestration/llm-ops-coding-handler.snapshot.valid.json")));
        snapshot.remove(List.of(
                "contractVersion", "profileVersionId", "profileKey", "profileVersion"));
        return snapshot;
    }

    private static JsonNode fullSnapshot(
            UUID id, int version, JsonNode authoring) {
        ObjectNode snapshot = OBJECT_MAPPER.createObjectNode();
        snapshot.put("contractVersion", "1.0");
        snapshot.put("profileVersionId", id.toString());
        snapshot.put("profileKey", "LLM_OPS");
        snapshot.put("profileVersion", version);
        for (String field : List.of(
                "nodes", "edges", "config", "modelBindings", "toolPolicy",
                "guardrailProfileKey")) {
            snapshot.set(field, authoring.path(field).deepCopy());
        }
        return snapshot;
    }
}

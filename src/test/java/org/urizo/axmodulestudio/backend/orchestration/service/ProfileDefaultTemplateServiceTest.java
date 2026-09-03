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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.urizo.axmodulestudio.backend.orchestration.repository.ProfileDefaultTemplateRepository;

class ProfileDefaultTemplateServiceTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final ProfileDefaultTemplateRepository repository =
            mock(ProfileDefaultTemplateRepository.class);
    private final ProfileModelBindingService bindings = mock(ProfileModelBindingService.class);
    private final ProfileDefaultTemplateService service =
            new ProfileDefaultTemplateService(repository, bindings);

    @Test
    void loadsAndSavesTemplatesWithinTheirProfileKey() throws Exception {
        JsonNode llmOps = authoringSnapshot(
                "llm-ops-coding-handler.snapshot.valid.json");
        ProfileDefaultTemplateRepository.StoredDefaultTemplate stored =
                stored("LLM_OPS", llmOps);
        when(repository.findByProfileKey("LLM_OPS")).thenReturn(Optional.of(stored));
        when(repository.save("LLM_OPS", llmOps)).thenReturn(stored);

        assertThat(service.get("LLM_OPS").snapshot()).isEqualTo(llmOps);
        assertThat(service.save("LLM_OPS", llmOps).profileKey()).isEqualTo("LLM_OPS");
        verify(bindings).validateCatalogSelections("LLM_OPS", llmOps);
        verify(repository, never()).findByProfileKey("NATURAL_CMS");
    }

    @Test
    void rejectsCrossProfileSnapshotsBeforeWriting() throws Exception {
        JsonNode naturalCms = authoringSnapshot(
                "natural-cms-handler.snapshot.valid.json");

        assertThatThrownBy(() -> service.save("LLM_OPS", naturalCms))
                .isInstanceOfSatisfying(ProfileVersionException.class, failure ->
                        assertThat(failure.code()).isEqualTo("CONTRACT_VALIDATION_FAILED"));
        verify(repository, never()).save(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsUnknownProfileKeys() throws Exception {
        assertThatThrownBy(() -> service.save(
                "UNKNOWN", authoringSnapshot("llm-ops-coding-handler.snapshot.valid.json")))
                .isInstanceOfSatisfying(ProfileVersionException.class, failure ->
                        assertThat(failure.status().value()).isEqualTo(400));
    }

    @Test
    void readsLegacyDefaultsButRequiresBindingsWhenSaving() throws Exception {
        ObjectNode legacy = (ObjectNode) authoringSnapshot(
                "llm-ops-coding-handler.snapshot.valid.json");
        legacy.remove("toolBindings");
        when(repository.findByProfileKey("LLM_OPS"))
                .thenReturn(Optional.of(stored("LLM_OPS", legacy)));

        assertThat(service.get("LLM_OPS").snapshot()).isEqualTo(legacy);
        assertThatThrownBy(() -> service.save("LLM_OPS", legacy))
                .isInstanceOfSatisfying(ProfileVersionException.class, failure ->
                        assertThat(failure.code()).isEqualTo("CONTRACT_VALIDATION_FAILED"));
        verify(repository, never()).save("LLM_OPS", legacy);
    }

    private static ProfileDefaultTemplateRepository.StoredDefaultTemplate stored(
            String profileKey, JsonNode snapshot) {
        return new ProfileDefaultTemplateRepository.StoredDefaultTemplate(
                profileKey, Instant.parse("2026-09-03T00:00:00Z"), snapshot);
    }

    private static JsonNode authoringSnapshot(String fixture) throws Exception {
        ObjectNode snapshot = (ObjectNode) OBJECT_MAPPER.readTree(Files.readString(Path.of(
                "contracts/fixtures/orchestration/" + fixture)));
        snapshot.remove(List.of(
                "contractVersion", "profileVersionId", "profileKey", "profileVersion"));
        return snapshot;
    }
}

package org.urizo.axmodulestudio.backend.orchestration.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.urizo.axmodulestudio.backend.orchestration.repository.ProfileDefaultTemplateRepository;
import org.urizo.axmodulestudio.backend.orchestration.repository.ProfileDefaultTemplateRepository.StoredDefaultTemplate;

@Service
@ConditionalOnProperty(prefix = "ax.coding.model-turn-bridge", name = "enabled", havingValue = "true")
public final class ProfileDefaultTemplateService {

    private final ProfileDefaultTemplateRepository repository;
    private final ProfileModelBindingService bindings;

    public ProfileDefaultTemplateService(ProfileDefaultTemplateRepository repository,
            ProfileModelBindingService bindings) {
        this.repository = repository;
        this.bindings = bindings;
    }

    public StoredDefaultTemplate get(String profileKey) {
        String normalized = requireProfileKey(profileKey);
        StoredDefaultTemplate stored = repository.findByProfileKey(normalized)
                .orElseThrow(() -> new ProfileVersionException(
                        "PROFILE_DEFAULT_TEMPLATE_NOT_FOUND",
                        "The default Profile Template was not found.",
                        HttpStatus.NOT_FOUND));
        ProfileSnapshotValidator.validateAuthoring(normalized, stored.snapshot());
        return stored;
    }

    public StoredDefaultTemplate save(String profileKey, JsonNode snapshot) {
        String normalized = requireProfileKey(profileKey);
        repository.findByProfileKey(normalized).ifPresent(current -> {
            if (dropsSelectionMetadata(current.snapshot(), snapshot)) {
                throw new ProfileVersionException(
                        "CONTRACT_VALIDATION_FAILED",
                        "modelBindings selections cannot be removed by this endpoint.",
                        HttpStatus.BAD_REQUEST);
            }
        });
        ProfileSnapshotValidator.validateAuthoring(normalized, snapshot);
        bindings.validateCatalogSelections(normalized, snapshot);
        return repository.save(normalized, snapshot);
    }

    private static boolean dropsSelectionMetadata(JsonNode current, JsonNode next) {
        JsonNode currentBindings = current.path("modelBindings");
        JsonNode nextBindings = next == null ? null : next.path("modelBindings");
        if (!currentBindings.isObject() || nextBindings == null || !nextBindings.isObject()) return false;
        java.util.Iterator<String> names = currentBindings.fieldNames();
        while (names.hasNext()) {
            String nodeId = names.next();
            if (currentBindings.path(nodeId).has("selections")
                    && !nextBindings.path(nodeId).has("selections")) return true;
        }
        return false;
    }

    private static String requireProfileKey(String profileKey) {
        if (!"LLM_OPS".equals(profileKey) && !"NATURAL_CMS".equals(profileKey)) {
            throw new ProfileVersionException(
                    "CONTRACT_VALIDATION_FAILED",
                    "profileKey must be LLM_OPS or NATURAL_CMS.",
                    HttpStatus.BAD_REQUEST);
        }
        return profileKey;
    }
}

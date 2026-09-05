package org.urizo.axmodulestudio.backend.orchestration.service;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.urizo.axmodulestudio.backend.orchestration.repository.ProfileVersionRepository;
import org.urizo.axmodulestudio.backend.orchestration.repository.ProfileVersionRepository.AdminStoredProfileVersion;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ModelUseCase;
import org.urizo.axmodulestudio.backend.integration.ai.local.LocalProviderSecretService;

@Service
@ConditionalOnProperty(prefix = "ax.coding.model-turn-bridge", name = "enabled", havingValue = "true")
public final class ProfileVersionService {

    private final ProfileVersionRepository repository;
    private final ProfileModelBindingService bindings;
    private final LocalProviderSecretService credentials;

    public ProfileVersionService(ProfileVersionRepository repository,
            ProfileModelBindingService bindings, LocalProviderSecretService credentials) {
        this.repository = repository;
        this.bindings = bindings;
        this.credentials = credentials;
    }

    public JsonNode getBound(String authorization, UUID profileVersionId) {
        ProfileVersionRepository.StoredProfileVersion stored = repository
                .findById(authorization, profileVersionId)
                .orElseThrow(() -> new ProfileVersionException(
                        "PROFILE_VERSION_NOT_FOUND",
                        "AI Profile Version was not found.",
                        HttpStatus.NOT_FOUND));
        if (!"ACTIVE".equals(stored.status()) && !"INACTIVE".equals(stored.status())) {
            throw new ProfileVersionException(
                    "PROFILE_VERSION_NOT_ACTIVE",
                    "AI Profile Version is not executable.",
                    HttpStatus.CONFLICT);
        }
        return stored.snapshot();
    }

    public List<AdminStoredProfileVersion> listAdmin(String profileKey) {
        String normalized = profileKey == null ? null : requireProfileKey(profileKey);
        return repository.findAll(normalized);
    }

    public AdminStoredProfileVersion createDraft(String profileKey, JsonNode authoringSnapshot) {
        String normalized = requireProfileKey(profileKey);
        ProfileSnapshotValidator.validateAuthoring(normalized, authoringSnapshot);
        bindings.validateCatalogSelections(normalized, authoringSnapshot);
        UUID profileVersionId = UUID.randomUUID();
        validateModels(normalized, profileVersionId, authoringSnapshot, true);
        return repository.createDraft(normalized, profileVersionId, authoringSnapshot);
    }

    public AdminStoredProfileVersion activate(UUID profileVersionId) {
        AdminStoredProfileVersion stored = repository.findAdminById(profileVersionId)
                .orElseThrow(ProfileVersionService::notFound);
        if ("INACTIVE".equals(stored.status())) {
            throw new ProfileVersionException(
                    "PROFILE_VERSION_CONFLICT",
                    "An INACTIVE Profile Version cannot be reactivated.",
                    HttpStatus.CONFLICT);
        }
        ProfileSnapshotValidator.validateForActivation(
                stored.profileVersionId(),
                stored.profileKey(),
                stored.profileVersion(),
                stored.snapshot());
        bindings.validateCatalogSelections(stored.profileKey(), stored.snapshot());
        validateModels(stored.profileKey(), stored.profileVersionId(), stored.snapshot(), false);
        return repository.activate(profileVersionId).orElseThrow(ProfileVersionService::notFound);
    }

    private void validateModels(String profileKey, UUID profileVersionId, JsonNode snapshot,
            boolean authoring) {
        ObjectNode full = snapshot.deepCopy();
        if (authoring) {
            full.put("profileVersionId", profileVersionId.toString());
            full.put("profileKey", profileKey);
        }
        for (JsonNode node : full.path("nodes")) {
            if (!"agent".equals(node.path("type").asText())) continue;
            if (authoring && !full.path("modelBindings").path(node.path("id").asText())
                    .has("selections")) continue;
            bindings.resolve(full, profileVersionId, node.path("id").asText(),
                    node.path("handlerKey").asText(), ModelUseCase.TOOL_CALL).forEach(model -> {
                        if (!credentials.hasVerifiedCredential(model.provider())) {
                            throw new ProfileVersionException("MODEL_CREDENTIAL_UNAVAILABLE",
                                    "The selected Provider Credential is unavailable.", HttpStatus.CONFLICT);
                        }
                    });
        }
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

    private static ProfileVersionException notFound() {
        return new ProfileVersionException(
                "PROFILE_VERSION_NOT_FOUND",
                "AI Profile Version was not found.",
                HttpStatus.NOT_FOUND);
    }
}

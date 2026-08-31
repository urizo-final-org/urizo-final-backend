package org.urizo.axmodulestudio.backend.orchestration.service;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.urizo.axmodulestudio.backend.orchestration.repository.ProfileVersionRepository;
import org.urizo.axmodulestudio.backend.orchestration.repository.ProfileVersionRepository.AdminStoredProfileVersion;

@Service
@ConditionalOnProperty(prefix = "ax.coding.model-turn-bridge", name = "enabled", havingValue = "true")
public final class ProfileVersionService {

    private final ProfileVersionRepository repository;

    public ProfileVersionService(ProfileVersionRepository repository) {
        this.repository = repository;
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
        return repository.createDraft(normalized, UUID.randomUUID(), authoringSnapshot);
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
        ProfileSnapshotValidator.validateStored(
                stored.profileVersionId(),
                stored.profileKey(),
                stored.profileVersion(),
                stored.snapshot());
        return repository.activate(profileVersionId).orElseThrow(ProfileVersionService::notFound);
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

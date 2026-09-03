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

    public ProfileDefaultTemplateService(ProfileDefaultTemplateRepository repository) {
        this.repository = repository;
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
        ProfileSnapshotValidator.validateAuthoring(normalized, snapshot);
        return repository.save(normalized, snapshot);
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

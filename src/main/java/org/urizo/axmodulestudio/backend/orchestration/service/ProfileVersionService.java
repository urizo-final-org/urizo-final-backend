package org.urizo.axmodulestudio.backend.orchestration.service;

import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.urizo.axmodulestudio.backend.orchestration.repository.ProfileVersionRepository;

@Service
@ConditionalOnProperty(prefix = "ax.coding.model-turn-bridge", name = "enabled", havingValue = "true")
public final class ProfileVersionService {

    private final ProfileVersionRepository repository;

    public ProfileVersionService(ProfileVersionRepository repository) {
        this.repository = repository;
    }

    public JsonNode getActive(String authorization, UUID profileVersionId) {
        ProfileVersionRepository.StoredProfileVersion stored = repository
                .findById(authorization, profileVersionId)
                .orElseThrow(() -> new ProfileVersionException(
                        "PROFILE_VERSION_NOT_FOUND",
                        "AI Profile Version was not found.",
                        HttpStatus.NOT_FOUND));
        if (!"ACTIVE".equals(stored.status())) {
            throw new ProfileVersionException(
                    "PROFILE_VERSION_NOT_ACTIVE",
                    "AI Profile Version is not active.",
                    HttpStatus.CONFLICT);
        }
        return stored.snapshot();
    }
}

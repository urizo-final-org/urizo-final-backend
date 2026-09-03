package org.urizo.axmodulestudio.backend.orchestration.repository;

import java.time.Instant;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;

public interface ProfileDefaultTemplateRepository {

    Optional<StoredDefaultTemplate> findByProfileKey(String profileKey);

    StoredDefaultTemplate save(String profileKey, JsonNode snapshot);

    record StoredDefaultTemplate(
            String profileKey,
            Instant updatedAt,
            JsonNode snapshot) {
        public StoredDefaultTemplate {
            snapshot = snapshot.deepCopy();
        }

        @Override
        public JsonNode snapshot() {
            return snapshot.deepCopy();
        }
    }
}

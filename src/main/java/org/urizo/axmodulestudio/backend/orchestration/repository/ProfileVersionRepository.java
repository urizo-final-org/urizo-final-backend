package org.urizo.axmodulestudio.backend.orchestration.repository;

import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

public interface ProfileVersionRepository {

    Optional<StoredProfileVersion> findById(String authorization, UUID profileVersionId);

    record StoredProfileVersion(String status, JsonNode snapshot) {
        public StoredProfileVersion {
            snapshot = snapshot.deepCopy();
        }

        @Override
        public JsonNode snapshot() {
            return snapshot.deepCopy();
        }
    }
}

package org.urizo.axmodulestudio.backend.orchestration.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

public interface ProfileVersionRepository {

    Optional<StoredProfileVersion> findById(String authorization, UUID profileVersionId);

    List<AdminStoredProfileVersion> findAll(String profileKey);

    Optional<AdminStoredProfileVersion> findAdminById(UUID profileVersionId);

    AdminStoredProfileVersion createDraft(
            String profileKey, UUID profileVersionId, JsonNode authoringSnapshot);

    Optional<AdminStoredProfileVersion> activate(UUID profileVersionId);

    record StoredProfileVersion(String status, JsonNode snapshot) {
        public StoredProfileVersion {
            snapshot = snapshot.deepCopy();
        }

        @Override
        public JsonNode snapshot() {
            return snapshot.deepCopy();
        }
    }

    record AdminStoredProfileVersion(
            UUID profileVersionId,
            String profileKey,
            int profileVersion,
            String status,
            Instant createdAt,
            JsonNode snapshot) {
        public AdminStoredProfileVersion {
            snapshot = snapshot.deepCopy();
        }

        @Override
        public JsonNode snapshot() {
            return snapshot.deepCopy();
        }
    }
}

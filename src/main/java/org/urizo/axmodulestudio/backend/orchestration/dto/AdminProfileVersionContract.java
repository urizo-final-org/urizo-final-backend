package org.urizo.axmodulestudio.backend.orchestration.dto;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import org.urizo.axmodulestudio.backend.orchestration.repository.ProfileVersionRepository;

public final class AdminProfileVersionContract {

    private AdminProfileVersionContract() { }

    public record CreateRequest(String profileKey, JsonNode snapshot) {
        public CreateRequest {
            snapshot = snapshot == null ? null : snapshot.deepCopy();
        }

        @Override
        public JsonNode snapshot() {
            return snapshot == null ? null : snapshot.deepCopy();
        }
    }

    public record ProfileVersionView(
            UUID profileVersionId,
            String profileKey,
            int profileVersion,
            String status,
            Instant createdAt,
            JsonNode snapshot) {
        public ProfileVersionView {
            snapshot = snapshot.deepCopy();
        }

        @Override
        public JsonNode snapshot() {
            return snapshot.deepCopy();
        }

        public static ProfileVersionView from(
                ProfileVersionRepository.AdminStoredProfileVersion stored) {
            return new ProfileVersionView(
                    stored.profileVersionId(),
                    stored.profileKey(),
                    stored.profileVersion(),
                    stored.status(),
                    stored.createdAt(),
                    stored.snapshot());
        }
    }
}

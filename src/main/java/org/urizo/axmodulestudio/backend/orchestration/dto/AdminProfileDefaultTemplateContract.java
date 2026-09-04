package org.urizo.axmodulestudio.backend.orchestration.dto;

import java.time.Instant;

import com.fasterxml.jackson.databind.JsonNode;
import org.urizo.axmodulestudio.backend.orchestration.repository.ProfileDefaultTemplateRepository.StoredDefaultTemplate;

public final class AdminProfileDefaultTemplateContract {

    private AdminProfileDefaultTemplateContract() { }

    public record SaveRequest(JsonNode snapshot) {
        public SaveRequest {
            snapshot = snapshot == null ? null : snapshot.deepCopy();
        }

        @Override
        public JsonNode snapshot() {
            return snapshot == null ? null : snapshot.deepCopy();
        }
    }

    public record DefaultTemplateView(
            String profileKey,
            Instant updatedAt,
            JsonNode snapshot) {
        public DefaultTemplateView {
            snapshot = snapshot.deepCopy();
        }

        @Override
        public JsonNode snapshot() {
            return snapshot.deepCopy();
        }

        public static DefaultTemplateView from(StoredDefaultTemplate stored) {
            return new DefaultTemplateView(
                    stored.profileKey(), stored.updatedAt(), stored.snapshot());
        }
    }
}

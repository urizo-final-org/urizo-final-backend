package org.urizo.axmodulestudio.backend.orchestration.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

public interface ProfileEditorLayoutRepository {

    Optional<StoredEditorLayout> findByProfileVersionId(UUID profileVersionId);

    SaveResult saveIfAbsent(UUID profileVersionId, JsonNode layout);

    record StoredEditorLayout(UUID profileVersionId, Instant createdAt, JsonNode layout) {
        public StoredEditorLayout {
            layout = layout.deepCopy();
        }

        @Override
        public JsonNode layout() {
            return layout.deepCopy();
        }
    }

    record SaveResult(StoredEditorLayout layout, boolean created) { }
}

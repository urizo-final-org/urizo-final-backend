package org.urizo.axmodulestudio.backend.orchestration.dto;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import org.urizo.axmodulestudio.backend.orchestration.repository.ProfileEditorLayoutRepository;

public final class AdminProfileEditorLayoutContract {

    private AdminProfileEditorLayoutContract() { }

    public record EditorLayoutView(
            UUID profileVersionId,
            Instant createdAt,
            JsonNode nodes) {
        public EditorLayoutView {
            nodes = nodes.deepCopy();
        }

        @Override
        public JsonNode nodes() {
            return nodes.deepCopy();
        }

        public static EditorLayoutView from(
                ProfileEditorLayoutRepository.StoredEditorLayout stored) {
            return new EditorLayoutView(
                    stored.profileVersionId(),
                    stored.createdAt(),
                    stored.layout().path("nodes"));
        }
    }
}

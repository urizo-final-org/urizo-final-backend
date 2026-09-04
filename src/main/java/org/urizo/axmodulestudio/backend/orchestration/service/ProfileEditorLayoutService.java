package org.urizo.axmodulestudio.backend.orchestration.service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.urizo.axmodulestudio.backend.orchestration.repository.ProfileEditorLayoutRepository;
import org.urizo.axmodulestudio.backend.orchestration.repository.ProfileEditorLayoutRepository.SaveResult;
import org.urizo.axmodulestudio.backend.orchestration.repository.ProfileEditorLayoutRepository.StoredEditorLayout;
import org.urizo.axmodulestudio.backend.orchestration.repository.ProfileVersionRepository;

@Service
@ConditionalOnProperty(prefix = "ax.coding.model-turn-bridge", name = "enabled", havingValue = "true")
public final class ProfileEditorLayoutService {

    private final ProfileVersionRepository profileVersions;
    private final ProfileEditorLayoutRepository layouts;
    private final ObjectMapper objectMapper;

    public ProfileEditorLayoutService(
            ProfileVersionRepository profileVersions,
            ProfileEditorLayoutRepository layouts,
            ObjectMapper objectMapper) {
        this.profileVersions = profileVersions;
        this.layouts = layouts;
        this.objectMapper = objectMapper;
    }

    public StoredEditorLayout get(UUID profileVersionId) {
        requireProfileVersion(profileVersionId);
        return layouts.findByProfileVersionId(profileVersionId)
                .orElseThrow(() -> new ProfileVersionException(
                        "PROFILE_EDITOR_LAYOUT_NOT_FOUND",
                        "The Profile Editor Layout was not found.",
                        HttpStatus.NOT_FOUND));
    }

    public SaveResult save(UUID profileVersionId, JsonNode request) {
        JsonNode snapshot = requireProfileVersion(profileVersionId).snapshot();
        return layouts.saveIfAbsent(
                profileVersionId,
                canonicalLayout(snapshot, request));
    }

    private ProfileVersionRepository.AdminStoredProfileVersion requireProfileVersion(
            UUID profileVersionId) {
        return profileVersions.findAdminById(profileVersionId)
                .orElseThrow(() -> new ProfileVersionException(
                        "PROFILE_VERSION_NOT_FOUND",
                        "AI Profile Version was not found.",
                        HttpStatus.NOT_FOUND));
    }

    private ObjectNode canonicalLayout(JsonNode snapshot, JsonNode request) {
        Set<String> expectedIds = snapshotNodeIds(snapshot);
        if (request == null
                || !request.isObject()
                || request.size() != 1
                || !request.path("nodes").isArray()) {
            throw invalidLayout();
        }
        JsonNode nodes = request.path("nodes");

        Map<String, Coordinates> coordinatesById = new LinkedHashMap<>();
        for (JsonNode node : nodes) {
            if (!node.isObject()
                    || node.size() != 3
                    || !node.path("id").isTextual()
                    || node.path("id").asText().isBlank()
                    || !node.path("x").isNumber()
                    || !node.path("y").isNumber()) {
                throw invalidLayout();
            }
            String id = node.path("id").asText();
            double x = node.path("x").asDouble();
            double y = node.path("y").asDouble();
            if (!Double.isFinite(x) || !Double.isFinite(y)
                    || coordinatesById.putIfAbsent(id, new Coordinates(x, y)) != null) {
                throw invalidLayout();
            }
        }
        if (!coordinatesById.keySet().equals(expectedIds)) {
            throw invalidLayout();
        }

        ArrayNode canonicalNodes = objectMapper.createArrayNode();
        for (String id : expectedIds) {
            Coordinates coordinates = coordinatesById.get(id);
            canonicalNodes.addObject()
                    .put("id", id)
                    .put("x", coordinates.x())
                    .put("y", coordinates.y());
        }
        ObjectNode layout = objectMapper.createObjectNode();
        layout.set("nodes", canonicalNodes);
        return layout;
    }

    private static Set<String> snapshotNodeIds(JsonNode snapshot) {
        JsonNode nodes = snapshot.path("nodes");
        if (!nodes.isArray()) throw unavailableSnapshot();
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode node : nodes) {
            if (!node.path("id").isTextual() || !ids.add(node.path("id").asText())) {
                throw unavailableSnapshot();
            }
        }
        return ids;
    }

    private static ProfileVersionException invalidLayout() {
        return new ProfileVersionException(
                "CONTRACT_VALIDATION_FAILED",
                "Layout nodes must contain exactly one id, x, and y for every Snapshot node.",
                HttpStatus.BAD_REQUEST);
    }

    private static ProfileVersionException unavailableSnapshot() {
        return new ProfileVersionException(
                "INTERNAL_TRANSIENT_ERROR",
                "The stored Profile Version Snapshot is temporarily unavailable.",
                HttpStatus.SERVICE_UNAVAILABLE,
                true,
                1_000L);
    }

    private record Coordinates(double x, double y) { }
}

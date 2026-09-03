package org.urizo.axmodulestudio.backend.orchestration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.urizo.axmodulestudio.backend.orchestration.repository.ProfileEditorLayoutRepository;
import org.urizo.axmodulestudio.backend.orchestration.repository.ProfileVersionRepository;

class ProfileEditorLayoutServiceTest {

    private static final UUID PROFILE_VERSION_ID =
            UUID.fromString("77777777-7777-4777-8777-777777777777");

    private final ProfileVersionRepository profileVersions = mock(ProfileVersionRepository.class);
    private final ProfileEditorLayoutRepository layouts =
            mock(ProfileEditorLayoutRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ProfileEditorLayoutService service =
            new ProfileEditorLayoutService(profileVersions, layouts, objectMapper);

    @BeforeEach
    void profileExists() {
        when(profileVersions.findAdminById(PROFILE_VERSION_ID))
                .thenReturn(Optional.of(profileVersion()));
    }

    @Test
    void savesOnlyIdAndCoordinatesInSnapshotNodeOrder() {
        ArrayNode requestNodes = objectMapper.createArrayNode();
        requestNodes.addObject().put("id", "end").put("x", 30).put("y", 40);
        requestNodes.addObject().put("id", "start").put("x", 10).put("y", 20);
        when(layouts.saveIfAbsent(eq(PROFILE_VERSION_ID), any())).thenAnswer(invocation -> {
            JsonNode layout = invocation.getArgument(1);
            return new ProfileEditorLayoutRepository.SaveResult(
                    stored(layout), true);
        });

        ProfileEditorLayoutRepository.SaveResult result =
                service.save(PROFILE_VERSION_ID, request(requestNodes));

        assertThat(result.created()).isTrue();
        ArgumentCaptor<JsonNode> layout = ArgumentCaptor.forClass(JsonNode.class);
        verify(layouts).saveIfAbsent(eq(PROFILE_VERSION_ID), layout.capture());
        assertThat(layout.getValue().toString()).isEqualTo(
                "{\"nodes\":[{\"id\":\"start\",\"x\":10.0,\"y\":20.0},"
                        + "{\"id\":\"end\",\"x\":30.0,\"y\":40.0}]}");
    }

    @Test
    void rejectsMissingUnknownDuplicateAndExtendedNodeLayouts() {
        ArrayNode missing = objectMapper.createArrayNode();
        missing.addObject().put("id", "start").put("x", 10).put("y", 20);

        ArrayNode unknown = validNodes();
        ((ObjectNode) unknown.get(1)).put("id", "unknown");

        ArrayNode duplicate = objectMapper.createArrayNode();
        duplicate.addObject().put("id", "start").put("x", 10).put("y", 20);
        duplicate.addObject().put("id", "start").put("x", 30).put("y", 40);

        ArrayNode extended = validNodes();
        ((ObjectNode) extended.get(0)).put("label", "Start");

        for (JsonNode invalid : new JsonNode[] {missing, unknown, duplicate, extended}) {
            assertThatThrownBy(() -> service.save(PROFILE_VERSION_ID, request(invalid)))
                    .isInstanceOfSatisfying(ProfileVersionException.class, failure -> {
                        assertThat(failure.code()).isEqualTo("CONTRACT_VALIDATION_FAILED");
                        assertThat(failure.status().value()).isEqualTo(400);
                    });
        }
        verify(layouts, never()).saveIfAbsent(any(), any());
    }

    @Test
    void rejectsNonNumericCoordinates() {
        ArrayNode nodes = validNodes();
        ((ObjectNode) nodes.get(0)).put("x", "left");

        assertThatThrownBy(() -> service.save(PROFILE_VERSION_ID, request(nodes)))
                .isInstanceOfSatisfying(ProfileVersionException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("CONTRACT_VALIDATION_FAILED"));
    }

    @Test
    void distinguishesMissingProfileAndMissingLayout() {
        when(layouts.findByProfileVersionId(PROFILE_VERSION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(PROFILE_VERSION_ID))
                .isInstanceOfSatisfying(ProfileVersionException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("PROFILE_EDITOR_LAYOUT_NOT_FOUND"));

        when(profileVersions.findAdminById(PROFILE_VERSION_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.save(PROFILE_VERSION_ID, request(validNodes())))
                .isInstanceOfSatisfying(ProfileVersionException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("PROFILE_VERSION_NOT_FOUND"));
    }

    @Test
    void rejectsFieldsOutsideTheNodesPayload() {
        ObjectNode request = request(validNodes());
        request.putArray("edges");

        assertThatThrownBy(() -> service.save(PROFILE_VERSION_ID, request))
                .isInstanceOfSatisfying(ProfileVersionException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("CONTRACT_VALIDATION_FAILED"));
        verify(layouts, never()).saveIfAbsent(any(), any());
    }

    private ArrayNode validNodes() {
        ArrayNode nodes = objectMapper.createArrayNode();
        nodes.addObject().put("id", "start").put("x", 10).put("y", 20);
        nodes.addObject().put("id", "end").put("x", 30).put("y", 40);
        return nodes;
    }

    private ObjectNode request(JsonNode nodes) {
        return objectMapper.createObjectNode().set("nodes", nodes);
    }

    private ProfileVersionRepository.AdminStoredProfileVersion profileVersion() {
        ObjectNode snapshot = objectMapper.createObjectNode();
        snapshot.putArray("nodes").addObject().put("id", "start");
        snapshot.withArray("nodes").addObject().put("id", "end");
        return new ProfileVersionRepository.AdminStoredProfileVersion(
                PROFILE_VERSION_ID,
                "LLM_OPS",
                1,
                "DRAFT",
                Instant.parse("2026-09-03T00:00:00Z"),
                snapshot);
    }

    private ProfileEditorLayoutRepository.StoredEditorLayout stored(JsonNode layout) {
        return new ProfileEditorLayoutRepository.StoredEditorLayout(
                PROFILE_VERSION_ID,
                Instant.parse("2026-09-03T00:00:00Z"),
                layout);
    }
}

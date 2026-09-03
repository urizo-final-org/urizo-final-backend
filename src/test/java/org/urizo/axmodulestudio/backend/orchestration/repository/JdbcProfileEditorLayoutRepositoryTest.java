package org.urizo.axmodulestudio.backend.orchestration.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.urizo.axmodulestudio.backend.orchestration.service.ProfileVersionException;

class JdbcProfileEditorLayoutRepositoryTest {

    private static final UUID PROFILE_VERSION_ID =
            UUID.fromString("77777777-7777-4777-8777-777777777777");

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final TransactionTemplate transactions = mock(TransactionTemplate.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JdbcProfileEditorLayoutRepository repository =
            new JdbcProfileEditorLayoutRepository(jdbc, transactions, objectMapper);

    @BeforeEach
    void executeTransactionCallbacks() {
        when(transactions.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
    }

    @Test
    void createsLayoutOnceAndReturnsTheStoredRow() throws Exception {
        JsonNode layout = layout(10, 20);
        when(jdbc.update(
                argThat(sql -> sql.contains("INSERT INTO app.ai_profile_editor_layout")),
                eq(PROFILE_VERSION_ID),
                anyString())).thenReturn(1);
        storedQuery(layout);

        ProfileEditorLayoutRepository.SaveResult result =
                repository.saveIfAbsent(PROFILE_VERSION_ID, layout);

        assertThat(result.created()).isTrue();
        assertThat(result.layout().layout()).isEqualTo(layout);
    }

    @Test
    void treatsTheSameStoredPayloadAsIdempotent() throws Exception {
        JsonNode layout = layout(10, 20);
        when(jdbc.update(anyString(), eq(PROFILE_VERSION_ID), anyString())).thenReturn(0);
        storedQuery(layout);

        ProfileEditorLayoutRepository.SaveResult result =
                repository.saveIfAbsent(PROFILE_VERSION_ID, layout);

        assertThat(result.created()).isFalse();
        assertThat(result.layout().layout()).isEqualTo(layout);
    }

    @Test
    void rejectsASecondDifferentPayload() throws Exception {
        when(jdbc.update(anyString(), eq(PROFILE_VERSION_ID), anyString())).thenReturn(0);
        storedQuery(layout(30, 40));

        assertThatThrownBy(() -> repository.saveIfAbsent(
                PROFILE_VERSION_ID, layout(10, 20)))
                .isInstanceOfSatisfying(ProfileVersionException.class, failure -> {
                    assertThat(failure.code()).isEqualTo("PROFILE_EDITOR_LAYOUT_CONFLICT");
                    assertThat(failure.status().value()).isEqualTo(409);
                });
    }

    @Test
    @SuppressWarnings("unchecked")
    void returnsEmptyWhenNoLayoutExists() {
        when(jdbc.query(
                argThat(sql -> sql.contains("FROM app.ai_profile_editor_layout")),
                any(RowMapper.class),
                eq(PROFILE_VERSION_ID))).thenReturn(List.of());

        assertThat(repository.findByProfileVersionId(PROFILE_VERSION_ID)).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private void storedQuery(JsonNode layout) throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getObject("profile_version_id", UUID.class))
                .thenReturn(PROFILE_VERSION_ID);
        when(resultSet.getObject("created_at", OffsetDateTime.class))
                .thenReturn(OffsetDateTime.parse("2026-09-03T00:00:00Z"));
        when(resultSet.getString("layout_json")).thenReturn(layout.toString());
        when(jdbc.query(
                argThat(sql -> sql.contains("FROM app.ai_profile_editor_layout")),
                any(RowMapper.class),
                eq(PROFILE_VERSION_ID))).thenAnswer(invocation -> {
                    RowMapper<ProfileEditorLayoutRepository.StoredEditorLayout> mapper =
                            invocation.getArgument(1);
                    return List.of(mapper.mapRow(resultSet, 0));
                });
    }

    private JsonNode layout(double x, double y) {
        JsonNode nodes = objectMapper.createArrayNode().add(
                objectMapper.createObjectNode()
                        .put("id", "start")
                        .put("x", x)
                        .put("y", y));
        return objectMapper.createObjectNode().set("nodes", nodes);
    }
}

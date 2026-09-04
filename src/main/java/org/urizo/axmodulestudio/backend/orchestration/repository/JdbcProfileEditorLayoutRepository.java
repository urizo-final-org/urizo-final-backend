package org.urizo.axmodulestudio.backend.orchestration.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import org.urizo.axmodulestudio.backend.orchestration.service.ProfileVersionException;

@Repository
@ConditionalOnProperty(prefix = "ax.coding.model-turn-bridge", name = "enabled", havingValue = "true")
public class JdbcProfileEditorLayoutRepository implements ProfileEditorLayoutRepository {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper objectMapper;

    public JdbcProfileEditorLayoutRepository(
            @Qualifier("codingModelTurnJdbcTemplate") JdbcTemplate jdbc,
            @Qualifier("codingModelTurnTransactionTemplate") TransactionTemplate transactions,
            ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<StoredEditorLayout> findByProfileVersionId(UUID profileVersionId) {
        try {
            Optional<StoredEditorLayout> result = transactions.execute(
                    status -> query(profileVersionId));
            if (result == null) throw unavailable();
            return result;
        }
        catch (ProfileVersionException failure) {
            throw failure;
        }
        catch (DataAccessException failure) {
            throw unavailable();
        }
    }

    @Override
    public SaveResult saveIfAbsent(UUID profileVersionId, JsonNode layout) {
        try {
            SaveResult result = transactions.execute(status -> {
                int inserted = jdbc.update("""
                        INSERT INTO app.ai_profile_editor_layout (
                            profile_version_id, layout_json
                        ) VALUES (?, CAST(? AS jsonb))
                        ON CONFLICT (profile_version_id) DO NOTHING
                        """, profileVersionId, encode(layout));
                StoredEditorLayout stored = query(profileVersionId).orElseThrow(
                        JdbcProfileEditorLayoutRepository::unavailable);
                if (!stored.layout().equals(layout)) {
                    throw conflict();
                }
                return new SaveResult(stored, inserted == 1);
            });
            if (result == null) throw unavailable();
            return result;
        }
        catch (ProfileVersionException failure) {
            throw failure;
        }
        catch (DataIntegrityViolationException failure) {
            throw conflict();
        }
        catch (DataAccessException failure) {
            throw unavailable();
        }
    }

    private Optional<StoredEditorLayout> query(UUID profileVersionId) {
        List<StoredEditorLayout> rows = jdbc.query("""
                SELECT profile_version_id, created_at, layout_json::text
                FROM app.ai_profile_editor_layout
                WHERE profile_version_id = ?
                """, (resultSet, rowNumber) -> new StoredEditorLayout(
                    resultSet.getObject("profile_version_id", UUID.class),
                    resultSet.getObject("created_at", OffsetDateTime.class).toInstant(),
                    decode(resultSet.getString("layout_json"))),
                profileVersionId);
        return rows.stream().findFirst();
    }

    private String encode(JsonNode layout) {
        try {
            return objectMapper.writeValueAsString(layout);
        }
        catch (JsonProcessingException failure) {
            throw validationFailed();
        }
    }

    private JsonNode decode(String value) {
        try {
            JsonNode layout = objectMapper.readTree(value);
            if (layout == null || !layout.isObject()) {
                throw new JsonProcessingException("stored editor layout is not an object") { };
            }
            return layout;
        }
        catch (JsonProcessingException failure) {
            throw unavailable();
        }
    }

    private static ProfileVersionException validationFailed() {
        return new ProfileVersionException(
                "CONTRACT_VALIDATION_FAILED",
                "The Profile Editor Layout is not valid JSON.",
                HttpStatus.BAD_REQUEST);
    }

    private static ProfileVersionException conflict() {
        return new ProfileVersionException(
                "PROFILE_EDITOR_LAYOUT_CONFLICT",
                "The Profile Editor Layout is immutable and already has different coordinates.",
                HttpStatus.CONFLICT);
    }

    private static ProfileVersionException unavailable() {
        return new ProfileVersionException(
                "INTERNAL_TRANSIENT_ERROR",
                "The Profile Editor Layout store is temporarily unavailable.",
                HttpStatus.SERVICE_UNAVAILABLE,
                true,
                1_000L);
    }
}

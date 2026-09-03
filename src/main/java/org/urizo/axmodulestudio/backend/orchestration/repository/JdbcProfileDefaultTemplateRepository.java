package org.urizo.axmodulestudio.backend.orchestration.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import org.urizo.axmodulestudio.backend.orchestration.service.ProfileVersionException;

@Repository
@ConditionalOnProperty(prefix = "ax.coding.model-turn-bridge", name = "enabled", havingValue = "true")
public class JdbcProfileDefaultTemplateRepository implements ProfileDefaultTemplateRepository {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper objectMapper;

    public JdbcProfileDefaultTemplateRepository(
            @Qualifier("codingModelTurnJdbcTemplate") JdbcTemplate jdbc,
            @Qualifier("codingModelTurnTransactionTemplate") TransactionTemplate transactions,
            ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<StoredDefaultTemplate> findByProfileKey(String profileKey) {
        try {
            Optional<StoredDefaultTemplate> result = transactions.execute(
                    status -> query(profileKey));
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
    public StoredDefaultTemplate save(String profileKey, JsonNode snapshot) {
        try {
            StoredDefaultTemplate result = transactions.execute(status -> {
                jdbc.update("""
                        INSERT INTO app.ai_profile_default_template (
                            profile_key, snapshot_json
                        ) VALUES (?, CAST(? AS jsonb))
                        ON CONFLICT (profile_key) DO UPDATE
                        SET snapshot_json = EXCLUDED.snapshot_json,
                            updated_at = CURRENT_TIMESTAMP
                        """, profileKey, encode(snapshot));
                return query(profileKey).orElseThrow(
                        JdbcProfileDefaultTemplateRepository::unavailable);
            });
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

    private Optional<StoredDefaultTemplate> query(String profileKey) {
        List<StoredDefaultTemplate> rows = jdbc.query("""
                SELECT profile_key, updated_at, snapshot_json::text
                FROM app.ai_profile_default_template
                WHERE profile_key = ?
                """, (resultSet, rowNumber) -> new StoredDefaultTemplate(
                    resultSet.getString("profile_key"),
                    resultSet.getObject("updated_at", OffsetDateTime.class).toInstant(),
                    decode(resultSet.getString("snapshot_json"))),
                profileKey);
        return rows.stream().findFirst();
    }

    private String encode(JsonNode snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        }
        catch (JsonProcessingException failure) {
            throw new ProfileVersionException(
                    "CONTRACT_VALIDATION_FAILED",
                    "The default Profile Template is not valid JSON.",
                    HttpStatus.BAD_REQUEST);
        }
    }

    private JsonNode decode(String value) {
        try {
            JsonNode snapshot = objectMapper.readTree(value);
            if (snapshot == null || !snapshot.isObject()) {
                throw new JsonProcessingException("stored default template is not an object") { };
            }
            return snapshot;
        }
        catch (JsonProcessingException failure) {
            throw unavailable();
        }
    }

    private static ProfileVersionException unavailable() {
        return new ProfileVersionException(
                "INTERNAL_TRANSIENT_ERROR",
                "The default Profile Template store is temporarily unavailable.",
                HttpStatus.SERVICE_UNAVAILABLE,
                true,
                1_000L);
    }
}

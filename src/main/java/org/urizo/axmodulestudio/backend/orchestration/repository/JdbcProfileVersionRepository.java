package org.urizo.axmodulestudio.backend.orchestration.repository;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import org.urizo.axmodulestudio.backend.orchestration.dto.ProfileVersionContract;
import org.urizo.axmodulestudio.backend.orchestration.service.ProfileVersionException;

@Repository
@ConditionalOnProperty(prefix = "ax.coding.model-turn-bridge", name = "enabled", havingValue = "true")
public class JdbcProfileVersionRepository implements ProfileVersionRepository {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public JdbcProfileVersionRepository(
            @Qualifier("codingModelTurnJdbcTemplate") JdbcTemplate jdbc,
            @Qualifier("codingModelTurnTransactionTemplate") TransactionTemplate transactions,
            ObjectMapper objectMapper,
            Clock clock) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public Optional<StoredProfileVersion> findById(
            String authorization, UUID profileVersionId) {
        byte[] digest = credentialDigest(authorization);
        try {
            Optional<StoredProfileVersion> result = transactions.execute(status -> {
                authenticate(digest);
                List<StoredProfileVersion> rows = jdbc.query("""
                        SELECT status, snapshot_json::text
                        FROM app.ai_profile_version
                        WHERE profile_version_id = ?
                        """,
                        (resultSet, rowNumber) -> new StoredProfileVersion(
                                resultSet.getString("status"),
                                decodeSnapshot(resultSet.getString("snapshot_json"))),
                        profileVersionId);
                return rows.stream().findFirst();
            });
            if (result == null) {
                throw unavailable();
            }
            return result;
        }
        catch (ProfileVersionException failure) {
            throw failure;
        }
        catch (DataAccessException failure) {
            throw unavailable();
        }
        finally {
            Arrays.fill(digest, (byte) 0);
        }
    }

    @Override
    public List<AdminStoredProfileVersion> findAll(String profileKey) {
        try {
            List<AdminStoredProfileVersion> result = transactions.execute(status ->
                    profileKey == null
                            ? jdbc.query("""
                                    SELECT profile_version_id, profile_key, profile_version,
                                           status, created_at, snapshot_json::text
                                    FROM app.ai_profile_version
                                    ORDER BY profile_key, profile_version DESC
                                    """, this::mapAdminVersion)
                            : jdbc.query("""
                                    SELECT profile_version_id, profile_key, profile_version,
                                           status, created_at, snapshot_json::text
                                    FROM app.ai_profile_version
                                    WHERE profile_key = ?
                                    ORDER BY profile_version DESC
                                    """, this::mapAdminVersion, profileKey));
            if (result == null) throw unavailable();
            return List.copyOf(result);
        }
        catch (ProfileVersionException failure) {
            throw failure;
        }
        catch (DataAccessException failure) {
            throw unavailable();
        }
    }

    @Override
    public Optional<AdminStoredProfileVersion> findAdminById(UUID profileVersionId) {
        try {
            Optional<AdminStoredProfileVersion> result = transactions.execute(status ->
                    queryAdminById(profileVersionId, false));
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
    public AdminStoredProfileVersion createDraft(
            String profileKey, UUID profileVersionId, JsonNode authoringSnapshot) {
        try {
            AdminStoredProfileVersion result = transactions.execute(status -> {
                lockProfileVersions();
                Integer profileVersion = jdbc.queryForObject("""
                        SELECT COALESCE(MAX(profile_version), 0) + 1
                        FROM app.ai_profile_version
                        WHERE profile_key = ?
                        """, Integer.class, profileKey);
                if (profileVersion == null || profileVersion < 1) throw unavailable();
                ObjectNode snapshot = fullSnapshot(
                        profileKey, profileVersionId, profileVersion, authoringSnapshot);
                jdbc.update("""
                        INSERT INTO app.ai_profile_version (
                            profile_version_id, profile_key, profile_version, snapshot_json
                        ) VALUES (?, ?, ?, CAST(? AS jsonb))
                        """,
                        profileVersionId,
                        profileKey,
                        profileVersion,
                        encodeSnapshot(snapshot));
                return queryAdminById(profileVersionId, false).orElseThrow(
                        JdbcProfileVersionRepository::unavailable);
            });
            if (result == null) throw unavailable();
            return result;
        }
        catch (ProfileVersionException failure) {
            throw failure;
        }
        catch (DataIntegrityViolationException failure) {
            throw conflict("A concurrent Profile Version change must be retried.");
        }
        catch (DataAccessException failure) {
            throw unavailable();
        }
    }

    @Override
    public Optional<AdminStoredProfileVersion> activate(UUID profileVersionId) {
        try {
            Optional<AdminStoredProfileVersion> result = transactions.execute(status -> {
                lockProfileVersions();
                Optional<AdminStoredProfileVersion> found = queryAdminById(profileVersionId, true);
                if (found.isEmpty()) return Optional.empty();
                AdminStoredProfileVersion target = found.orElseThrow();
                if ("ACTIVE".equals(target.status())) return Optional.of(target);
                if (!"DRAFT".equals(target.status())) {
                    throw conflict("Only a DRAFT Profile Version can be activated.");
                }
                jdbc.update("""
                        UPDATE app.ai_profile_version
                        SET status = 'INACTIVE'
                        WHERE profile_key = ? AND status = 'ACTIVE'
                        """, target.profileKey());
                int activated = jdbc.update("""
                        UPDATE app.ai_profile_version
                        SET status = 'ACTIVE'
                        WHERE profile_version_id = ? AND status = 'DRAFT'
                        """, profileVersionId);
                if (activated != 1) {
                    throw conflict("The Profile Version activation state changed concurrently.");
                }
                return queryAdminById(profileVersionId, false);
            });
            if (result == null) throw unavailable();
            return result;
        }
        catch (ProfileVersionException failure) {
            throw failure;
        }
        catch (DataIntegrityViolationException failure) {
            throw conflict("The Profile Version could not be activated.");
        }
        catch (DataAccessException failure) {
            throw unavailable();
        }
    }

    private Optional<AdminStoredProfileVersion> queryAdminById(
            UUID profileVersionId, boolean forUpdate) {
        String lock = forUpdate ? " FOR UPDATE" : "";
        List<AdminStoredProfileVersion> rows = jdbc.query("""
                SELECT profile_version_id, profile_key, profile_version,
                       status, created_at, snapshot_json::text
                FROM app.ai_profile_version
                WHERE profile_version_id = ?
                """ + lock, this::mapAdminVersion, profileVersionId);
        return rows.stream().findFirst();
    }

    private AdminStoredProfileVersion mapAdminVersion(
            java.sql.ResultSet resultSet, int rowNumber) throws java.sql.SQLException {
        return new AdminStoredProfileVersion(
                resultSet.getObject("profile_version_id", UUID.class),
                resultSet.getString("profile_key"),
                resultSet.getInt("profile_version"),
                resultSet.getString("status"),
                resultSet.getObject("created_at", OffsetDateTime.class).toInstant(),
                decodeSnapshot(resultSet.getString("snapshot_json")));
    }

    private void lockProfileVersions() {
        jdbc.execute("LOCK TABLE app.ai_profile_version IN SHARE ROW EXCLUSIVE MODE");
    }

    private ObjectNode fullSnapshot(
            String profileKey,
            UUID profileVersionId,
            int profileVersion,
            JsonNode authoringSnapshot) {
        ObjectNode snapshot = objectMapper.createObjectNode();
        snapshot.put("contractVersion", ProfileVersionContract.SCHEMA_VERSION);
        snapshot.put("profileVersionId", profileVersionId.toString());
        snapshot.put("profileKey", profileKey);
        snapshot.put("profileVersion", profileVersion);
        for (String field : List.of(
                "nodes", "edges", "config", "modelBindings", "toolBindings", "toolPolicy",
                "guardrailProfileKey")) {
            snapshot.set(field, authoringSnapshot.path(field).deepCopy());
        }
        return snapshot;
    }

    private String encodeSnapshot(JsonNode snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        }
        catch (JsonProcessingException failure) {
            throw new ProfileVersionException(
                    "CONTRACT_VALIDATION_FAILED",
                    "The Profile Version Snapshot is not valid JSON.",
                    HttpStatus.BAD_REQUEST);
        }
    }

    private void authenticate(byte[] digest) {
        Instant now = clock.instant();
        OffsetDateTime databaseNow = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
        List<UUID> credentials = jdbc.query("""
                SELECT credential_id
                FROM app.coding_service_credential
                WHERE credential_digest = ?
                  AND status IN ('ACTIVE', 'RETIRING')
                  AND valid_from <= ?
                  AND (valid_until IS NULL OR valid_until > ?)
                FOR UPDATE
                """,
                (resultSet, rowNumber) -> resultSet.getObject("credential_id", UUID.class),
                digest, databaseNow, databaseNow);
        if (credentials.size() != 1) {
            throw new ProfileVersionException(
                    "SERVICE_AUTHENTICATION_FAILED",
                    "Service authentication failed.",
                    HttpStatus.UNAUTHORIZED);
        }
        jdbc.update(
                "UPDATE app.coding_service_credential SET last_used_at = ? WHERE credential_id = ?",
                databaseNow, credentials.get(0));
    }

    private JsonNode decodeSnapshot(String value) {
        try {
            JsonNode snapshot = objectMapper.readTree(value);
            if (snapshot == null || !snapshot.isObject()) {
                throw new JsonProcessingException("stored Snapshot is not an object") { };
            }
            return snapshot;
        }
        catch (JsonProcessingException failure) {
            throw unavailable();
        }
    }

    private static byte[] credentialDigest(String authorization) {
        if (authorization == null || authorization.length() < 8 || authorization.length() > 519
                || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new ProfileVersionException(
                    "SERVICE_AUTHENTICATION_FAILED",
                    "Service authentication failed.",
                    HttpStatus.UNAUTHORIZED);
        }
        byte[] credential = new byte[authorization.length() - 7];
        try {
            for (int index = 7; index < authorization.length(); index++) {
                char value = authorization.charAt(index);
                if (value < 0x21 || value > 0x7e) {
                    throw new ProfileVersionException(
                            "SERVICE_AUTHENTICATION_FAILED",
                            "Service authentication failed.",
                            HttpStatus.UNAUTHORIZED);
                }
                credential[index - 7] = (byte) value;
            }
            return MessageDigest.getInstance("SHA-256").digest(credential);
        }
        catch (NoSuchAlgorithmException failure) {
            throw unavailable();
        }
        finally {
            Arrays.fill(credential, (byte) 0);
        }
    }

    private static ProfileVersionException unavailable() {
        return new ProfileVersionException(
                "INTERNAL_TRANSIENT_ERROR",
                "The AI Profile Version store is temporarily unavailable.",
                HttpStatus.SERVICE_UNAVAILABLE,
                true,
                1_000L);
    }

    private static ProfileVersionException conflict(String message) {
        return new ProfileVersionException(
                "PROFILE_VERSION_CONFLICT", message, HttpStatus.CONFLICT);
    }
}

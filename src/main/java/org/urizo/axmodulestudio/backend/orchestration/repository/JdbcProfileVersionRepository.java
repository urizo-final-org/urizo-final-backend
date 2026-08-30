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
}

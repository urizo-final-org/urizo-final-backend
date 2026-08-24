package org.urizo.axmodulestudio.backend.integration.ai.local;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ModelProvider;
import org.urizo.axmodulestudio.backend.integration.ai.local.ProviderSecretCrypto.EncryptedSecret;

@Repository
@Profile("dev")
public class EncryptedProviderSecretRepository {

    private final JdbcTemplate jdbcTemplate;

    public EncryptedProviderSecretRepository(JdbcTemplate localProviderSecretJdbcTemplate) {
        this.jdbcTemplate = localProviderSecretJdbcTemplate;
    }

    public void upsert(ModelProvider provider, EncryptedSecret encryptedSecret) {
        jdbcTemplate.update("""
                INSERT INTO app.local_provider_secret (
                    provider, encrypted_value, nonce, fingerprint, key_version, connection_state
                ) VALUES (?, ?, ?, ?, ?, 'STORED')
                ON CONFLICT (provider) DO UPDATE SET
                    encrypted_value = EXCLUDED.encrypted_value,
                    nonce = EXCLUDED.nonce,
                    fingerprint = EXCLUDED.fingerprint,
                    key_version = EXCLUDED.key_version,
                    connection_state = 'STORED',
                    updated_at = CURRENT_TIMESTAMP,
                    last_tested_at = NULL
                """,
                provider.name(),
                encryptedSecret.ciphertext(),
                encryptedSecret.nonce(),
                encryptedSecret.fingerprint(),
                encryptedSecret.keyVersion());
    }

    public Optional<StoredProviderSecret> find(ModelProvider provider) {
        List<StoredProviderSecret> matches = jdbcTemplate.query("""
                SELECT provider, encrypted_value, nonce, fingerprint, key_version,
                       connection_state, updated_at, last_tested_at
                  FROM app.local_provider_secret
                 WHERE provider = ?
                """, this::mapStoredSecret, provider.name());
        return matches.stream().findFirst();
    }

    public List<StoredProviderSecret> findAll() {
        return jdbcTemplate.query("""
                SELECT provider, encrypted_value, nonce, fingerprint, key_version,
                       connection_state, updated_at, last_tested_at
                  FROM app.local_provider_secret
                 ORDER BY provider
                """, this::mapStoredSecret);
    }

    public void updateState(ModelProvider provider, ProviderCredentialState state) {
        int changed = jdbcTemplate.update("""
                UPDATE app.local_provider_secret
                   SET connection_state = ?, last_tested_at = CURRENT_TIMESTAMP
                 WHERE provider = ?
                """, state.name(), provider.name());
        if (changed != 1) {
            throw new IllegalStateException("Provider credential state update did not target exactly one row.");
        }
    }

    public void recordAudit(
            ModelProvider provider,
            String modelId,
            String outcome,
            String safeErrorCode,
            Integer inputTokens,
            Integer outputTokens,
            long latencyMs) {
        jdbcTemplate.update("""
                INSERT INTO app.local_provider_connection_audit (
                    audit_id, provider, model_id, capability, outcome, safe_error_code,
                    input_tokens, output_tokens, latency_ms
                ) VALUES (?, ?, ?, 'CHAT', ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                provider.name(),
                modelId,
                outcome,
                safeErrorCode,
                inputTokens,
                outputTokens,
                latencyMs);
    }

    private StoredProviderSecret mapStoredSecret(ResultSet resultSet, int rowNumber) throws SQLException {
        java.sql.Timestamp lastTested = resultSet.getTimestamp("last_tested_at");
        return new StoredProviderSecret(
                ModelProvider.valueOf(resultSet.getString("provider")),
                new EncryptedSecret(
                        resultSet.getBytes("encrypted_value"),
                        resultSet.getBytes("nonce"),
                        resultSet.getString("fingerprint"),
                        resultSet.getShort("key_version")),
                ProviderCredentialState.valueOf(resultSet.getString("connection_state")),
                resultSet.getTimestamp("updated_at").toInstant(),
                lastTested == null ? null : lastTested.toInstant());
    }
}

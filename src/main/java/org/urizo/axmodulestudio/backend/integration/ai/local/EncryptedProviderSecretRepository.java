package org.urizo.axmodulestudio.backend.integration.ai.local;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ModelProvider;
import org.urizo.axmodulestudio.backend.integration.ai.local.ProviderSecretCrypto.EncryptedSecret;

@Repository
@Profile("dev")
public class EncryptedProviderSecretRepository {

    private final JdbcTemplate jdbcTemplate;

    public EncryptedProviderSecretRepository(
            @Qualifier("localProviderSecretJdbcTemplate") JdbcTemplate localProviderSecretJdbcTemplate) {
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

    public boolean recordTestIfCurrent(
            ModelProvider provider,
            String credentialFingerprint,
            String modelId,
            ProviderCredentialState state,
            String outcome,
            String safeErrorCode,
            Integer inputTokens,
            Integer outputTokens,
            long latencyMs) {
        int changed = jdbcTemplate.update("""
                WITH current_credential AS (
                    UPDATE app.local_provider_secret
                       SET connection_state = ?, last_tested_at = CURRENT_TIMESTAMP
                     WHERE provider = ?
                       AND fingerprint = ?
                    RETURNING provider
                )
                INSERT INTO app.local_provider_connection_audit (
                    audit_id, provider, model_id, capability, outcome, safe_error_code,
                    input_tokens, output_tokens, latency_ms
                )
                SELECT ?, provider, ?, 'CHAT', ?, ?, ?, ?, ?
                  FROM current_credential
                """,
                state.name(),
                provider.name(),
                credentialFingerprint,
                UUID.randomUUID(),
                modelId,
                outcome,
                safeErrorCode,
                inputTokens,
                outputTokens,
                latencyMs);
        return changed == 1;
    }

    public void delete(ModelProvider provider) {
        jdbcTemplate.update("""
                DELETE FROM app.local_provider_secret
                 WHERE provider = ?
                """, provider.name());
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

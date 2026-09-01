package org.urizo.axmodulestudio.backend.integration.ai.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ModelProvider;

class EncryptedProviderSecretRepositoryTest {

    @Test
    void stateAndAuditAreOneConditionalStatementBoundToTheTestedFingerprint() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        EncryptedProviderSecretRepository repository = new EncryptedProviderSecretRepository(jdbc);
        when(jdbc.update(
                contains("WITH current_credential AS"),
                eq("VERIFIED"),
                eq("OPENAI"),
                eq("old-fingerprint"),
                any(UUID.class),
                eq("fixture-model"),
                eq("PASSED"),
                isNull(),
                eq(5),
                eq(1),
                eq(12L))).thenReturn(0);

        boolean updated = repository.recordTestIfCurrent(
                ModelProvider.OPENAI,
                "old-fingerprint",
                "fixture-model",
                ProviderCredentialState.VERIFIED,
                "PASSED",
                null,
                5,
                1,
                12L);

        assertThat(updated).isFalse();
        verify(jdbc).update(
                contains("INSERT INTO app.local_provider_connection_audit"),
                eq("VERIFIED"),
                eq("OPENAI"),
                eq("old-fingerprint"),
                any(UUID.class),
                eq("fixture-model"),
                eq("PASSED"),
                isNull(),
                eq(5),
                eq(1),
                eq(12L));
    }
}

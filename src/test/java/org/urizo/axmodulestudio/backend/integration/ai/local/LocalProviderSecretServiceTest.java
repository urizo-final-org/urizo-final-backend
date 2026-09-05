package org.urizo.axmodulestudio.backend.integration.ai.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ModelProvider;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderCredentialLease;
import org.urizo.axmodulestudio.backend.integration.ai.local.ProviderSecretCrypto.EncryptedSecret;

@ExtendWith(MockitoExtension.class)
class LocalProviderSecretServiceTest {

    @Mock
    private EncryptedProviderSecretRepository repository;

    @Mock
    private ProviderSecretCrypto crypto;

    @InjectMocks
    private LocalProviderSecretService service;

    @Test
    void deleteRemovesTheEncryptedRowAndReturnsOnlyUnconfiguredStatus() {
        ProviderCredentialStatus result = service.delete(ModelProvider.OPENAI);

        verify(repository).delete(ModelProvider.OPENAI);
        assertThat(result.provider()).isEqualTo(ModelProvider.OPENAI);
        assertThat(result.configured()).isFalse();
        assertThat(result.state()).isNull();
        assertThat(result.fingerprintSuffix()).isNull();
    }

    @Test
    void deleteRejectsProvidersOutsideTheLocalCredentialAllowlist() {
        assertThatThrownBy(() -> service.delete(ModelProvider.VERTEX_AI_GEMINI))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Provider is not supported by the local CMS.");
        verifyNoInteractions(repository, crypto);
    }

    @Test
    void storeRejectsMaskedAnthropicKeysBeforeEncryption() {
        assertThatThrownBy(() -> service.store(ModelProvider.ANTHROPIC, "sk-ant-api03-masked...1234"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Anthropic API key format is invalid or masked.");
        verifyNoInteractions(repository, crypto);
    }

    @Test
    void createsAFingerprintCorrelatedLeaseAndClearsTheDecryptedBuffer() {
        String fixture = "fixture-only-not-a-real-key";
        byte[] decrypted = fixture.getBytes(StandardCharsets.US_ASCII);
        EncryptedSecret encrypted = new EncryptedSecret(
                new byte[] {1, 2, 3},
                new byte[12],
                "hmac-sha256:current-fingerprint",
                (short) 1);
        StoredProviderSecret stored = new StoredProviderSecret(
                ModelProvider.OPENAI,
                encrypted,
                ProviderCredentialState.STORED,
                Instant.parse("2026-08-11T06:00:00Z"),
                null);
        when(repository.find(ModelProvider.OPENAI)).thenReturn(Optional.of(stored));
        when(crypto.decrypt(ModelProvider.OPENAI, encrypted)).thenReturn(decrypted);

        ProviderCredentialLease lease = service.leaseForProviderCall(ModelProvider.OPENAI);

        assertThat(decrypted).containsOnly((byte) 0);
        assertThat(new String(lease.copySecret(), StandardCharsets.US_ASCII)).isEqualTo(fixture);
        assertThat(lease.credentialFingerprint()).isEqualTo("hmac-sha256:current-fingerprint");
        assertThat(lease.toString())
                .contains("REDACTED")
                .doesNotContain(fixture)
                .doesNotContain("current-fingerprint");

        lease.close();
        assertThatThrownBy(lease::copySecret)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Provider credential lease is closed.");
    }
}

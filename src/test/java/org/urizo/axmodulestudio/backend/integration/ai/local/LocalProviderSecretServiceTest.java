package org.urizo.axmodulestudio.backend.integration.ai.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ModelProvider;

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
}

package org.urizo.axmodulestudio.backend.integration.ai.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ModelGatewayErrorCode;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ModelProvider;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderCredentialLease;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderGatewayException;

class CmsProviderCredentialResolverTest {

    private final LocalProviderSecretService secretService = mock(LocalProviderSecretService.class);
    private final CmsProviderCredentialResolver resolver = new CmsProviderCredentialResolver(secretService);

    @Test
    void resolvesThroughAnAutoClosingVersionedRedactedLease() {
        String fixture = "fixture-only-not-a-real-key";
        ProviderCredentialLease storedLease = ProviderCredentialLease.fromBytes(
                ModelProvider.OPENAI,
                fixture.getBytes(StandardCharsets.US_ASCII),
                "fingerprint-current");
        when(secretService.leaseForProviderCall(ModelProvider.OPENAI)).thenReturn(storedLease);

        ProviderCredentialLease lease = resolver.resolve(ModelProvider.OPENAI);

        assertThat(new String(lease.copySecret(), StandardCharsets.US_ASCII)).isEqualTo(fixture);
        assertThat(lease.credentialFingerprint()).isEqualTo("fingerprint-current");
        assertThat(lease.toString()).doesNotContain(fixture).contains("REDACTED");

        lease.close();
        assertThatThrownBy(lease::copySecret)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Provider credential lease is closed.");
    }

    @Test
    void mapsMissingCredentialsWithoutPropagatingTheUnderlyingMessage() {
        String rawMessage = "raw-secret-like-message-must-not-leak";
        when(secretService.leaseForProviderCall(ModelProvider.GOOGLE_GENAI))
                .thenThrow(new IllegalArgumentException(rawMessage));

        assertThatThrownBy(() -> resolver.resolve(ModelProvider.GOOGLE_GENAI))
                .isInstanceOfSatisfying(ProviderGatewayException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(ModelGatewayErrorCode.MODEL_NOT_CONFIGURED);
                    assertThat(failure.getMessage()).isEqualTo("Provider credential is not configured.");
                    assertThat(failure.toString()).doesNotContain(rawMessage);
                });
    }
}

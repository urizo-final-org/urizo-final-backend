package org.urizo.axmodulestudio.backend.dev.cms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.urizo.axmodulestudio.backend.ai.gateway.ModelProvider;

class ProviderSecretCryptoTest {

    @Test
    void encryptsWithProviderBoundAadAndRoundTrips() {
        byte[] masterKey = new byte[32];
        Arrays.fill(masterKey, (byte) 7);
        ProviderSecretCrypto crypto = new ProviderSecretCrypto(masterKey);
        byte[] plaintext = "fixture-only-not-a-real-key".getBytes(StandardCharsets.US_ASCII);

        ProviderSecretCrypto.EncryptedSecret encrypted = crypto.encrypt(ModelProvider.OPENAI, plaintext);

        assertThat(encrypted.ciphertext()).isNotEqualTo(plaintext);
        assertThat(encrypted.nonce()).hasSize(12);
        assertThat(encrypted.fingerprint()).matches("hmac-sha256:[0-9a-f]{64}");
        assertThat(crypto.decrypt(ModelProvider.OPENAI, encrypted)).isEqualTo(plaintext);
        assertThatThrownBy(() -> crypto.decrypt(ModelProvider.ANTHROPIC, encrypted))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Local provider credential decryption failed.");
    }
}

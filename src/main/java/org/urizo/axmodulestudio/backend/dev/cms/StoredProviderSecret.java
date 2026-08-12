package org.urizo.axmodulestudio.backend.dev.cms;

import java.time.Instant;
import java.util.Objects;

import org.urizo.axmodulestudio.backend.ai.gateway.ModelProvider;
import org.urizo.axmodulestudio.backend.dev.cms.ProviderSecretCrypto.EncryptedSecret;

public record StoredProviderSecret(
        ModelProvider provider,
        EncryptedSecret encryptedSecret,
        ProviderCredentialState state,
        Instant updatedAt,
        Instant lastTestedAt) {

    public StoredProviderSecret {
        provider = Objects.requireNonNull(provider, "provider is required");
        encryptedSecret = Objects.requireNonNull(encryptedSecret, "encryptedSecret is required");
        state = Objects.requireNonNull(state, "state is required");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt is required");
    }
}

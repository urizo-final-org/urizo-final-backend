package org.urizo.axmodulestudio.backend.dev.cms;

import java.time.Instant;
import java.util.Objects;

import org.urizo.axmodulestudio.backend.ai.gateway.ModelProvider;

public record ProviderCredentialStatus(
        ModelProvider provider,
        boolean configured,
        ProviderCredentialState state,
        String fingerprintSuffix,
        Instant updatedAt,
        Instant lastTestedAt) {

    public ProviderCredentialStatus {
        provider = Objects.requireNonNull(provider, "provider is required");
        if (configured) {
            state = Objects.requireNonNull(state, "state is required when configured");
            fingerprintSuffix = Objects.requireNonNull(fingerprintSuffix, "fingerprintSuffix is required");
            updatedAt = Objects.requireNonNull(updatedAt, "updatedAt is required when configured");
        }
    }

    static ProviderCredentialStatus notConfigured(ModelProvider provider) {
        return new ProviderCredentialStatus(provider, false, null, null, null, null);
    }
}

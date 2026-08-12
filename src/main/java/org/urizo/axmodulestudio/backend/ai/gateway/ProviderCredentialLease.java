package org.urizo.axmodulestudio.backend.ai.gateway;

import java.util.Arrays;
import java.util.Objects;

public final class ProviderCredentialLease implements AutoCloseable {

    private final ModelProvider provider;
    private final byte[] secret;
    private boolean closed;

    private ProviderCredentialLease(ModelProvider provider, byte[] secret) {
        this.provider = Objects.requireNonNull(provider, "provider is required");
        Objects.requireNonNull(secret, "secret is required");
        if (secret.length == 0) {
            throw new IllegalArgumentException("Provider credential cannot be empty.");
        }
        this.secret = secret.clone();
    }

    public static ProviderCredentialLease fromBytes(ModelProvider provider, byte[] secret) {
        return new ProviderCredentialLease(provider, secret);
    }

    public ModelProvider provider() {
        return provider;
    }

    public synchronized byte[] copySecret() {
        if (closed) {
            throw new IllegalStateException("Provider credential lease is closed.");
        }
        return secret.clone();
    }

    @Override
    public synchronized void close() {
        if (!closed) {
            Arrays.fill(secret, (byte) 0);
            closed = true;
        }
    }

    @Override
    public String toString() {
        return "ProviderCredentialLease[provider=" + provider + ", secret=REDACTED]";
    }
}

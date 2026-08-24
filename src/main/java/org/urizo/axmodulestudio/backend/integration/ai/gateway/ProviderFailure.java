package org.urizo.axmodulestudio.backend.integration.ai.gateway;

import java.time.Duration;
import java.util.Objects;

public final class ProviderFailure extends RuntimeException {

    private final ProviderFailureKind kind;
    private final Duration retryAfter;

    public ProviderFailure(ProviderFailureKind kind, Duration retryAfter) {
        super("Provider request failed");
        this.kind = Objects.requireNonNull(kind, "kind is required");
        this.retryAfter = retryAfter;
    }

    public ProviderFailureKind kind() {
        return kind;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}

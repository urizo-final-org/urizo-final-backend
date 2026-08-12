package org.urizo.axmodulestudio.backend.ai.gateway;

public enum ProviderFailureKind {
    RATE_LIMITED,
    TIMEOUT,
    UNAVAILABLE,
    INVALID_RESPONSE,
    TRANSIENT
}

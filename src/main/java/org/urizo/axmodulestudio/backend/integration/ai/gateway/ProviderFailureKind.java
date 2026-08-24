package org.urizo.axmodulestudio.backend.integration.ai.gateway;

public enum ProviderFailureKind {
    RATE_LIMITED,
    TIMEOUT,
    UNAVAILABLE,
    INVALID_RESPONSE,
    TRANSIENT
}

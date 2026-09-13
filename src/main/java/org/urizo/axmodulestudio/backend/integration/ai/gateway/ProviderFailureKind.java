package org.urizo.axmodulestudio.backend.integration.ai.gateway;

public enum ProviderFailureKind {
    AUTHENTICATION,
    BILLING,
    QUOTA,
    MODEL_ACCESS,
    RATE_LIMITED,
    TIMEOUT,
    UNAVAILABLE,
    INVALID_RESPONSE,
    TRANSIENT
}

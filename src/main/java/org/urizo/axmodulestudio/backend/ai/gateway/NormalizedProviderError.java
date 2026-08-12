package org.urizo.axmodulestudio.backend.ai.gateway;

import java.time.Duration;

public record NormalizedProviderError(
        ModelGatewayErrorCode code,
        String message,
        boolean retryable,
        Duration retryAfter) {

    public NormalizedProviderError {
        if (code == null || message == null || message.isBlank()) {
            throw new IllegalArgumentException("error code and safe message are required");
        }
        if (retryable && (retryAfter == null || retryAfter.isZero() || retryAfter.isNegative())) {
            throw new IllegalArgumentException("retryable errors require a positive retryAfter");
        }
        if (!retryable && retryAfter != null) {
            throw new IllegalArgumentException("non-retryable errors cannot include retryAfter");
        }
        if (retryAfter != null && retryAfter.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("retryAfter exceeds the contract maximum");
        }
    }
}

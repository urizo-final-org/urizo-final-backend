package org.urizo.axmodulestudio.backend.integration.ai.gateway;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;

public final class ProviderErrorNormalizer {

    public NormalizedProviderError normalize(Throwable failure) {
        Objects.requireNonNull(failure, "failure is required");
        if (failure instanceof ProviderFailure providerFailure) {
            return normalize(providerFailure.kind(), providerFailure.retryAfter());
        }
        if (failure instanceof TimeoutException) {
            return normalize(ProviderFailureKind.TIMEOUT, null);
        }
        if (failure instanceof TransientAiException) {
            return normalize(ProviderFailureKind.TRANSIENT, null);
        }
        if (failure instanceof NonTransientAiException) {
            return normalize(ProviderFailureKind.INVALID_RESPONSE, null);
        }
        return normalize(ProviderFailureKind.INVALID_RESPONSE, null);
    }

    private NormalizedProviderError normalize(ProviderFailureKind kind, Duration requestedRetryAfter) {
        return switch (kind) {
            case RATE_LIMITED -> retryable(
                    ModelGatewayErrorCode.MODEL_RATE_LIMITED,
                    "Model provider rate limit reached.",
                    requestedRetryAfter,
                    Duration.ofSeconds(1));
            case TIMEOUT -> retryable(
                    ModelGatewayErrorCode.MODEL_TIMEOUT,
                    "Model provider deadline exceeded.",
                    requestedRetryAfter,
                    Duration.ofMillis(250));
            case UNAVAILABLE -> retryable(
                    ModelGatewayErrorCode.MODEL_PROVIDER_UNAVAILABLE,
                    "Model provider is temporarily unavailable.",
                    requestedRetryAfter,
                    Duration.ofSeconds(1));
            case TRANSIENT -> retryable(
                    ModelGatewayErrorCode.INTERNAL_TRANSIENT_ERROR,
                    "A transient model gateway failure occurred.",
                    requestedRetryAfter,
                    Duration.ofMillis(500));
            case INVALID_RESPONSE -> new NormalizedProviderError(
                    ModelGatewayErrorCode.MODEL_RESPONSE_INVALID,
                    "Model provider response failed validation.",
                    false,
                    null);
        };
    }

    private static NormalizedProviderError retryable(
            ModelGatewayErrorCode code,
            String safeMessage,
            Duration requested,
            Duration fallback) {
        Duration selected = requested == null ? fallback : requested;
        if (selected.isZero() || selected.isNegative() || selected.compareTo(Duration.ofHours(1)) > 0) {
            selected = fallback;
        }
        return new NormalizedProviderError(code, safeMessage, true, selected);
    }
}

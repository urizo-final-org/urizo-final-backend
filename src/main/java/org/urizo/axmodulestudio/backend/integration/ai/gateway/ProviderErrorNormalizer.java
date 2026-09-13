package org.urizo.axmodulestudio.backend.integration.ai.gateway;

import java.time.Duration;
import java.net.SocketTimeoutException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;

public final class ProviderErrorNormalizer {

    public NormalizedProviderError normalize(Throwable failure) {
        Objects.requireNonNull(failure, "failure is required");
        // SDKs and HTTP clients wrap transport errors. Bound traversal, never copy raw causes.
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 16; depth++, current = current.getCause()) {
            if (current instanceof ProviderFailure providerFailure) {
                return normalize(providerFailure.kind(), providerFailure.retryAfter());
            }
            if (current instanceof TimeoutException || current instanceof SocketTimeoutException
                    || current instanceof HttpTimeoutException) {
                return normalize(ProviderFailureKind.TIMEOUT, null);
            }
            if (current instanceof ConnectException || current instanceof UnknownHostException
                    || current instanceof java.net.SocketException) {
                return normalize(ProviderFailureKind.UNAVAILABLE, null);
            }
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
            // Keep the public error contract stable; these need a different configured
            // candidate, not another attempt with the same credential/model.
            case AUTHENTICATION -> configurationFailure("Model provider authentication failed.");
            case BILLING -> configurationFailure("Model provider billing or credit is unavailable.");
            case QUOTA -> configurationFailure("Model provider quota is exhausted.");
            case MODEL_ACCESS -> configurationFailure("Configured model access is unavailable.");
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

    private static NormalizedProviderError configurationFailure(String message) {
        return new NormalizedProviderError(ModelGatewayErrorCode.MODEL_NOT_CONFIGURED,
                message, false, null);
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

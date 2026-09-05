package org.urizo.axmodulestudio.backend.integration.ai.observability;

import java.util.Objects;

/** Safe, response-only facts captured after one model call completes. */
public record ModelObservation(
        String provider,
        String modelId,
        int inputTokens,
        int outputTokens,
        long latencyMs) {

    public ModelObservation {
        provider = requireText(provider, "provider");
        modelId = requireText(modelId, "modelId");
        if (inputTokens < 0 || outputTokens < 0 || latencyMs < 0) {
            throw new IllegalArgumentException(
                    "Model observation token counters and latency cannot be negative.");
        }
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field + " is required").strip();
        if (normalized.isEmpty() || normalized.length() > 255) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }
}

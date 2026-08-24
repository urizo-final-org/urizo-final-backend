package org.urizo.axmodulestudio.backend.integration.ai.local;

import java.time.Instant;
import java.util.Objects;

import org.urizo.axmodulestudio.backend.integration.ai.gateway.ModelProvider;

public record ProviderConnectionTestResult(
        ModelProvider provider,
        String modelId,
        ProviderCredentialState state,
        boolean inferenceExecuted,
        Integer inputTokens,
        Integer outputTokens,
        long latencyMs,
        Instant testedAt,
        String safeCode) {

    public ProviderConnectionTestResult {
        provider = Objects.requireNonNull(provider, "provider is required");
        modelId = Objects.requireNonNull(modelId, "modelId is required");
        state = Objects.requireNonNull(state, "state is required");
        testedAt = Objects.requireNonNull(testedAt, "testedAt is required");
        safeCode = Objects.requireNonNull(safeCode, "safeCode is required");
        if (latencyMs < 0) {
            throw new IllegalArgumentException("latencyMs cannot be negative");
        }
    }
}

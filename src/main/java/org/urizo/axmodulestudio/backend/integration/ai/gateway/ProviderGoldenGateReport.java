package org.urizo.axmodulestudio.backend.integration.ai.gateway;

import java.time.Duration;
import java.util.Objects;

public record ProviderGoldenGateReport(
        ModelProvider provider,
        String modelId,
        int completedRequests,
        int toolCallCandidates,
        int toolExecutions,
        int inputTokens,
        int outputTokens,
        Duration totalLatency) {

    public ProviderGoldenGateReport {
        provider = Objects.requireNonNull(provider, "provider is required");
        modelId = Objects.requireNonNull(modelId, "modelId is required");
        totalLatency = Objects.requireNonNull(totalLatency, "totalLatency is required");
        if (modelId.isBlank()) {
            throw new IllegalArgumentException("modelId cannot be blank");
        }
        if (completedRequests < 1
                || toolCallCandidates < 0
                || toolExecutions < 0
                || inputTokens < 0
                || outputTokens < 0
                || totalLatency.isNegative()) {
            throw new IllegalArgumentException("golden gate report contains invalid counters");
        }
        if (toolExecutions != 0) {
            throw new IllegalArgumentException("golden gate reports cannot contain tool executions");
        }
    }

    public int totalTokens() {
        return Math.addExact(inputTokens, outputTokens);
    }
}

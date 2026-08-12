package org.urizo.axmodulestudio.backend.ai.gateway;

import java.time.Duration;
import java.util.Objects;

public record ProviderProbeResult(
        boolean responsePresent,
        boolean structuredOutputSchemaValid,
        int toolCallCandidates,
        int toolExecutions,
        int inputTokens,
        int outputTokens,
        Duration latency) {

    public ProviderProbeResult {
        latency = Objects.requireNonNull(latency, "latency is required");
        if (toolCallCandidates < 0 || toolExecutions < 0 || inputTokens < 0 || outputTokens < 0) {
            throw new IllegalArgumentException("probe counters cannot be negative");
        }
        if (toolExecutions > toolCallCandidates) {
            throw new IllegalArgumentException("tool executions cannot exceed tool call candidates");
        }
        if (latency.isNegative()) {
            throw new IllegalArgumentException("latency cannot be negative");
        }
    }

    public int totalTokens() {
        return Math.addExact(inputTokens, outputTokens);
    }
}

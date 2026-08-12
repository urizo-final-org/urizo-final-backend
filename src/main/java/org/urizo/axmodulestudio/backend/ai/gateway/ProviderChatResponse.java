package org.urizo.axmodulestudio.backend.ai.gateway;

import java.time.Duration;
import java.util.Objects;

public record ProviderChatResponse(
        ModelProvider provider,
        String modelId,
        String content,
        int inputTokens,
        int outputTokens,
        Duration latency) {

    public ProviderChatResponse {
        provider = Objects.requireNonNull(provider, "provider is required");
        modelId = Objects.requireNonNull(modelId, "modelId is required");
        content = Objects.requireNonNull(content, "content is required");
        latency = Objects.requireNonNull(latency, "latency is required");
        if (modelId.isBlank() || content.isBlank()) {
            throw new IllegalArgumentException("modelId and content cannot be blank");
        }
        if (inputTokens < 0 || outputTokens < 0 || latency.isNegative()) {
            throw new IllegalArgumentException("token counters and latency cannot be negative");
        }
    }

    @Override
    public String toString() {
        return "ProviderChatResponse[provider=" + provider
                + ", modelId=" + modelId
                + ", content=REDACTED, inputTokens=" + inputTokens
                + ", outputTokens=" + outputTokens
                + ", latency=" + latency + "]";
    }
}

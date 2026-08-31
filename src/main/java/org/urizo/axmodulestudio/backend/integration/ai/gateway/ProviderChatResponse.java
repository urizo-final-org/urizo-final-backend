package org.urizo.axmodulestudio.backend.integration.ai.gateway;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record ProviderChatResponse(
        ModelProvider provider,
        String modelId,
        String content,
        List<ProviderChatMessage.ToolCall> toolCalls,
        int inputTokens,
        int outputTokens,
        Duration latency) {

    private static final int MAX_TOOL_CALLS = 50;

    public ProviderChatResponse {
        provider = Objects.requireNonNull(provider, "provider is required");
        modelId = Objects.requireNonNull(modelId, "modelId is required");
        content = Objects.requireNonNull(content, "content is required");
        toolCalls = List.copyOf(Objects.requireNonNull(toolCalls, "toolCalls are required"));
        latency = Objects.requireNonNull(latency, "latency is required");
        if (modelId.isBlank() || content.isBlank() && toolCalls.isEmpty()) {
            throw new IllegalArgumentException(
                    "modelId and assistant response cannot be blank");
        }
        if (toolCalls.size() > MAX_TOOL_CALLS) {
            throw new IllegalArgumentException("assistant tool calls exceed the response bound");
        }
        Set<String> ids = new HashSet<>();
        for (ProviderChatMessage.ToolCall call : toolCalls) {
            if (!ids.add(call.id())) {
                throw new IllegalArgumentException("assistant tool call ids must be unique");
            }
        }
        if (inputTokens < 0 || outputTokens < 0 || latency.isNegative()) {
            throw new IllegalArgumentException("token counters and latency cannot be negative");
        }
    }

    public ProviderChatResponse(
            ModelProvider provider,
            String modelId,
            String content,
            int inputTokens,
            int outputTokens,
            Duration latency) {
        this(provider, modelId, content, List.of(), inputTokens, outputTokens, latency);
    }

    @Override
    public String toString() {
        return "ProviderChatResponse[provider=" + provider
                + ", modelId=" + modelId
                + ", content=REDACTED, toolCalls=" + toolCalls.size()
                + ", inputTokens=" + inputTokens
                + ", outputTokens=" + outputTokens
                + ", latency=" + latency + "]";
    }
}

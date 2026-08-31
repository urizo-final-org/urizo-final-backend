package org.urizo.axmodulestudio.backend.integration.ai.gateway;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record ProviderChatRequest(
        ModelProvider provider,
        String modelId,
        List<ProviderChatMessage> messages,
        Instant deadline) {

    private static final int MAX_MESSAGES = 200;
    private static final int MAX_REQUEST_CHARACTERS = 65_536;

    public ProviderChatRequest {
        provider = Objects.requireNonNull(provider, "provider is required");
        modelId = Objects.requireNonNull(modelId, "modelId is required");
        messages = List.copyOf(Objects.requireNonNull(messages, "messages are required"));
        deadline = Objects.requireNonNull(deadline, "deadline is required");
        if (modelId.isBlank()) {
            throw new IllegalArgumentException("modelId cannot be blank");
        }
        long characters = messages.stream()
                .mapToLong(message -> message.content().length()
                        + message.toolCalls().stream()
                                .mapToLong(call -> call.arguments().length())
                                .sum())
                .sum();
        if (messages.isEmpty() || messages.size() > MAX_MESSAGES
                || characters > MAX_REQUEST_CHARACTERS) {
            throw new IllegalArgumentException("messages exceed the bounded chat request");
        }
    }

    public ProviderChatRequest(
            ModelProvider provider,
            String modelId,
            String prompt,
            Instant deadline) {
        this(provider, modelId,
                List.of(ProviderChatMessage.plain(
                        ProviderChatMessage.Role.USER,
                        Objects.requireNonNull(prompt, "prompt is required"))),
                deadline);
    }

    /**
     * Retains the old redacted diagnostic projection for local fixtures. Product
     * adapters consume {@link #messages()} and never collapse role boundaries.
     */
    public String prompt() {
        return messages.stream()
                .map(message -> "[" + message.role().name().toLowerCase()
                        + "]\n" + message.content())
                .reduce((left, right) -> left + "\n\n" + right)
                .orElse("");
    }

    @Override
    public String toString() {
        return "ProviderChatRequest[provider=" + provider
                + ", modelId=" + modelId
                + ", messages=REDACTED, deadline=" + deadline + "]";
    }
}

package org.urizo.axmodulestudio.backend.integration.ai.gateway;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * @param jsonObjectResponse asks the provider for a bare JSON object through its
 *     own response-format setting. Only a CHAT request may set it: a tool-calling
 *     request carries its own reply shape, and this lane keeps structured output
 *     and tool calling in separate request modes.
 */
public record ProviderChatRequest(
        ModelProvider provider,
        String modelId,
        List<ProviderChatMessage> messages,
        Instant deadline,
        boolean jsonObjectResponse) {

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

    /** Plain text remains the default reply shape for every existing caller. */
    public ProviderChatRequest(
            ModelProvider provider,
            String modelId,
            List<ProviderChatMessage> messages,
            Instant deadline) {
        this(provider, modelId, messages, deadline, false);
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
                + ", messages=REDACTED, deadline=" + deadline
                + ", jsonObjectResponse=" + jsonObjectResponse + "]";
    }
}

package org.urizo.axmodulestudio.backend.integration.ai.gateway;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
        List<ProviderToolDefinition> tools,
        Instant deadline,
        boolean jsonObjectResponse) {

    private static final int MAX_MESSAGES = 200;
    private static final int MAX_TOOLS = 50;
    private static final int MAX_REQUEST_CHARACTERS = 65_536;

    public ProviderChatRequest {
        provider = Objects.requireNonNull(provider, "provider is required");
        modelId = Objects.requireNonNull(modelId, "modelId is required");
        messages = List.copyOf(Objects.requireNonNull(messages, "messages are required"));
        tools = List.copyOf(Objects.requireNonNull(tools, "tools are required"));
        deadline = Objects.requireNonNull(deadline, "deadline is required");
        if (modelId.isBlank()) {
            throw new IllegalArgumentException("modelId cannot be blank");
        }
        List<ProviderToolDefinition> legacyTools =
                ProviderToolDefinition.legacyDefinitions(messages);
        if (!legacyTools.isEmpty()) {
            if (tools.isEmpty()) {
                tools = legacyTools;
            }
            else if (!tools.equals(legacyTools)) {
                throw new IllegalArgumentException(
                        "Explicit tools do not match the declared compatibility tools.");
            }
        }
        Map<String, ProviderToolDefinition> definitions = new HashMap<>();
        for (ProviderToolDefinition tool : tools) {
            if (definitions.putIfAbsent(tool.name(), tool) != null) {
                throw new IllegalArgumentException("Tool names must be unique.");
            }
        }
        if (!tools.isEmpty()) {
            validateToolHistory(messages, definitions);
        }
        List<ProviderChatMessage> providerMessages = legacyTools.isEmpty()
                ? messages : messages.subList(1, messages.size());
        long characters = providerMessages.stream()
                .mapToLong(message -> message.content().length()
                        + message.toolCalls().stream()
                                .mapToLong(call -> call.arguments().length())
                                .sum())
                .sum()
                + tools.stream()
                        .mapToLong(tool -> tool.name().length()
                                + tool.description().length()
                                + tool.providerInputSchema().length())
                        .sum();
        if (messages.isEmpty() || messages.size() > MAX_MESSAGES
                || tools.size() > MAX_TOOLS
                || characters > MAX_REQUEST_CHARACTERS) {
            throw new IllegalArgumentException("chat request exceeds its collection or size bounds");
        }
    }

    /** Plain text remains the default reply shape for every existing caller. */
    public ProviderChatRequest(
            ModelProvider provider,
            String modelId,
            List<ProviderChatMessage> messages,
            Instant deadline) {
        this(provider, modelId, messages, List.of(), deadline, false);
    }

    public ProviderChatRequest(
            ModelProvider provider,
            String modelId,
            List<ProviderChatMessage> messages,
            Instant deadline,
            boolean jsonObjectResponse) {
        this(provider, modelId, messages, List.of(), deadline, jsonObjectResponse);
    }

    public ProviderChatRequest(
            ModelProvider provider,
            String modelId,
            List<ProviderChatMessage> messages,
            List<ProviderToolDefinition> tools,
            Instant deadline) {
        this(provider, modelId, messages, tools, deadline, false);
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
                List.of(), deadline, false);
    }

    public List<ProviderChatMessage> providerMessages() {
        return legacyToolEnvelope()
                ? List.copyOf(messages.subList(1, messages.size()))
                : messages;
    }

    public boolean legacyToolEnvelope() {
        return !messages.isEmpty()
                && messages.get(0).role() == ProviderChatMessage.Role.SYSTEM
                && messages.get(0).content().startsWith(
                        ProviderToolDefinition.LEGACY_TOOL_PROMPT_PREFIX);
    }

    /**
     * Retains the old redacted diagnostic projection for local fixtures. Product
     * adapters consume {@link #providerMessages()} and never collapse role boundaries.
     */
    public String prompt() {
        return providerMessages().stream()
                .map(message -> "[" + message.role().name().toLowerCase()
                        + "]\n" + message.content())
                .reduce((left, right) -> left + "\n\n" + right)
                .orElse("");
    }

    @Override
    public String toString() {
        return "ProviderChatRequest[provider=" + provider
                + ", modelId=" + modelId
                + ", messages=REDACTED, tools=" + tools.size()
                + ", deadline=" + deadline
                + ", jsonObjectResponse=" + jsonObjectResponse + "]";
    }

    private static void validateToolHistory(
            List<ProviderChatMessage> messages,
            Map<String, ProviderToolDefinition> definitions) {
        Map<String, String> pending = new HashMap<>();
        Set<String> seen = new HashSet<>();
        for (ProviderChatMessage message : messages) {
            if (message.role() != ProviderChatMessage.Role.TOOL && !pending.isEmpty()) {
                throw new IllegalArgumentException(
                        "Assistant tool calls require matching tool results.");
            }
            for (ProviderChatMessage.ToolCall call : message.toolCalls()) {
                ProviderToolDefinition definition = definitions.get(call.name());
                if (definition == null) {
                    throw new IllegalArgumentException("Message references an undeclared tool.");
                }
                definition.normalizeArguments(call.arguments());
                if (!seen.add(call.id()) || pending.putIfAbsent(call.id(), call.name()) != null) {
                    throw new IllegalArgumentException("Assistant tool call ids must be unique.");
                }
            }
            if (message.role() == ProviderChatMessage.Role.TOOL) {
                if (!definitions.containsKey(message.toolName())) {
                    throw new IllegalArgumentException("Tool result references an undeclared tool.");
                }
                String expectedName = pending.remove(message.toolCallId());
                if (!message.toolName().equals(expectedName)) {
                    throw new IllegalArgumentException(
                            "Tool results must match pending assistant tool calls.");
                }
            }
        }
        if (!pending.isEmpty()) {
            throw new IllegalArgumentException(
                    "Assistant tool calls require matching tool results.");
        }
    }
}

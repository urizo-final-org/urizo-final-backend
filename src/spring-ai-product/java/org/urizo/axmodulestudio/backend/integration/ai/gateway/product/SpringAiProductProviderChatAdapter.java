package org.urizo.axmodulestudio.backend.integration.ai.gateway.product;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.context.annotation.Profile;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.StructuredOutputChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ModelProvider;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderChatAdapter;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderChatMessage;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderChatRequest;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderChatResponse;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderCredentialLease;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderCredentialResolver;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderFailure;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderFailureKind;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderFinishReason;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderModelRegistration;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderResponseFormat;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderToolDefinition;

@Component
@Profile("dev & !coding-model-turn-local-mock")
final class SpringAiProductProviderChatAdapter implements ProviderChatAdapter {

    private static final int MAX_NATIVE_TOOL_CALLS = 50;
    private static final int MAX_THOUGHT_SIGNATURE_BYTES = 65_536;
    private static final int MAX_THOUGHT_SIGNATURES = 1_024;
    private static final Duration THOUGHT_SIGNATURE_TTL = Duration.ofMinutes(10);
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ProviderCredentialResolver credentialResolver;
    private final Map<ModelProvider, ProductChatModelFactory> factories;
    private final Clock clock;
    private final Map<ThoughtSignatureKey, StoredThoughtSignature> thoughtSignatures =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(
                        Map.Entry<ThoughtSignatureKey, StoredThoughtSignature> eldest) {
                    return size() > MAX_THOUGHT_SIGNATURES;
                }
            };

    SpringAiProductProviderChatAdapter(
            ProviderCredentialResolver credentialResolver,
            List<ProductChatModelFactory> factories,
            Clock clock) {
        this.credentialResolver = credentialResolver;
        this.clock = clock;
        Map<ModelProvider, ProductChatModelFactory> indexed = new EnumMap<>(ModelProvider.class);
        for (ProductChatModelFactory factory : factories) {
            if (indexed.putIfAbsent(factory.provider(), factory) != null) {
                throw new IllegalArgumentException("Duplicate Product Lane chat model factory.");
            }
        }
        this.factories = Map.copyOf(indexed);
    }

    @Override
    public Set<ModelProvider> providers() {
        return factories.keySet();
    }

    @Override
    public ProviderChatResponse chat(
            ProviderModelRegistration registration,
            ProviderChatRequest request) {
        if (registration.provider() != request.provider()
                || !registration.modelId().equals(request.modelId())) {
            throw new IllegalArgumentException("Provider chat registration does not match the request.");
        }
        ProductChatModelFactory factory = factories.get(request.provider());
        if (factory == null) {
            throw new ProviderFailure(ProviderFailureKind.INVALID_RESPONSE, null);
        }

        Instant startedAt = clock.instant();
        try (ProviderCredentialLease lease = credentialResolver.resolve(request.provider())) {
            byte[] credentialBytes = lease.copySecret();
            try {
                String credential = new String(credentialBytes, StandardCharsets.US_ASCII);
                try (ProductChatModelSession session = factory.open(
                        credential,
                        registration.modelId(),
                        registration.maxOutputTokens())) {
                    ChatResponse response = session.chatModel().call(prompt(request));
                    return response(request, response, startedAt);
                }
            }
            finally {
                Arrays.fill(credentialBytes, (byte) 0);
            }
        }
    }

    private Prompt prompt(ProviderChatRequest request) {
        List<ProviderChatMessage> providerMessages = request.providerMessages();
        List<Message> messages = new ArrayList<>();
        for (int index = 0; index < providerMessages.size(); index++) {
            messages.add(springMessage(request, providerMessages.get(index), index));
        }
        if (request.tools().isEmpty() && !request.responseFormat().structured()) {
            return new Prompt(messages);
        }
        if (request.responseFormat().structured()) {
            return new Prompt(messages, structuredOptions(
                    request.provider(), request.responseFormat()));
        }
        List<ToolCallback> callbacks = request.tools().stream()
                .map(SpringAiProductProviderChatAdapter::toolCallback)
                .toList();
        ToolCallingChatOptions options = toolOptions(request.provider(), callbacks);
        return new Prompt(messages, options);
    }

    private static StructuredOutputChatOptions structuredOptions(
            ModelProvider provider,
            ProviderResponseFormat responseFormat) {
        StructuredOutputChatOptions options = switch (provider) {
            case OPENAI -> new OpenAiChatOptions();
            case GOOGLE_GENAI -> {
                GoogleGenAiChatOptions google = new GoogleGenAiChatOptions();
                google.setResponseMimeType("application/json");
                yield google;
            }
            case ANTHROPIC -> new AnthropicChatOptions();
            case VERTEX_AI_GEMINI ->
                    throw new ProviderFailure(ProviderFailureKind.INVALID_RESPONSE, null);
        };
        options.setOutputSchema(responseFormat.providerOutputSchema());
        return options;
    }

    private static ToolCallingChatOptions toolOptions(
            ModelProvider provider, List<ToolCallback> callbacks) {
        return switch (provider) {
            case OPENAI -> {
                OpenAiChatOptions options = new OpenAiChatOptions();
                options.setToolCallbacks(callbacks);
                options.setInternalToolExecutionEnabled(false);
                options.setParallelToolCalls(false);
                yield options;
            }
            case GOOGLE_GENAI -> {
                GoogleGenAiChatOptions options = new GoogleGenAiChatOptions();
                options.setToolCallbacks(callbacks);
                options.setInternalToolExecutionEnabled(false);
                yield options;
            }
            case ANTHROPIC -> {
                AnthropicChatOptions options = new AnthropicChatOptions();
                options.setToolCallbacks(callbacks);
                options.setInternalToolExecutionEnabled(false);
                yield options;
            }
            case VERTEX_AI_GEMINI ->
                    throw new ProviderFailure(ProviderFailureKind.INVALID_RESPONSE, null);
        };
    }

    private static ToolCallback toolCallback(ProviderToolDefinition tool) {
        return FunctionToolCallback.<String, String>builder(
                        tool.name(), input -> {
                            throw new IllegalStateException(
                                    "Provider-native tool callbacks cannot execute inside the chat adapter.");
                        })
                .description(tool.description())
                .inputType(String.class)
                .inputSchema(tool.providerInputSchema())
                .build();
    }

    private Message springMessage(
            ProviderChatRequest request,
            ProviderChatMessage message,
            int messageIndex) {
        return switch (message.role()) {
            case SYSTEM -> new SystemMessage(message.content());
            case USER -> new UserMessage(message.content());
            case ASSISTANT -> assistantMessage(request, message, messageIndex);
            case TOOL -> ToolResponseMessage.builder()
                    .responses(List.of(new ToolResponseMessage.ToolResponse(
                            message.toolCallId(), message.toolName(), message.content())))
                    .build();
        };
    }

    private AssistantMessage assistantMessage(
            ProviderChatRequest request,
            ProviderChatMessage message,
            int messageIndex) {
        AssistantMessage.Builder builder = AssistantMessage.builder()
                .content(message.content())
                .toolCalls(message.toolCalls().stream()
                        .map(call -> new AssistantMessage.ToolCall(
                                call.id(), "function", call.name(), call.arguments()))
                        .toList());
        List<byte[]> restored = restoreThoughtSignatures(request, message, messageIndex);
        if (!restored.isEmpty()) {
            builder.properties(Map.of("thoughtSignatures", restored));
        }
        return builder.build();
    }

    private ProviderChatResponse response(
            ProviderChatRequest request,
            ChatResponse response,
            Instant startedAt) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            throw new ProviderFailure(ProviderFailureKind.INVALID_RESPONSE, null);
        }
        AssistantMessage output = response.getResult().getOutput();
        String content = output.getText() == null ? "" : output.getText();
        List<ProviderChatMessage.ToolCall> toolCalls = nativeToolCalls(
                request, content, output.getToolCalls());
        storeThoughtSignatures(request, output, toolCalls);
        String compatibleContent = request.legacyToolEnvelope()
                ? legacyEnvelope(content, toolCalls) : content;
        Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
        int inputTokens = usage == null ? 0 : nonNegative(usage.getPromptTokens());
        int outputTokens = usage == null ? 0 : nonNegative(usage.getCompletionTokens());
        Duration latency = Duration.between(startedAt, clock.instant());
        String nativeFinishReason = response.getResult().getMetadata() == null
                ? null : response.getResult().getMetadata().getFinishReason();
        ProviderFinishReason finishReason = ProviderFinishReason.normalize(
                request.provider(), nativeFinishReason, !toolCalls.isEmpty());
        return new ProviderChatResponse(
                request.provider(),
                request.modelId(),
                compatibleContent,
                toolCalls,
                inputTokens,
                outputTokens,
                latency.isNegative() ? Duration.ZERO : latency,
                finishReason);
    }

    private static List<ProviderChatMessage.ToolCall> nativeToolCalls(
            ProviderChatRequest request,
            String assistantContent,
            List<AssistantMessage.ToolCall> nativeCalls) {
        if (nativeCalls == null || nativeCalls.isEmpty()) {
            return List.of();
        }
        if (nativeCalls.size() > MAX_NATIVE_TOOL_CALLS) {
            throw new ProviderFailure(ProviderFailureKind.INVALID_RESPONSE, null);
        }
        if (request.legacyToolEnvelope() && nativeCalls.size() > 1) {
            throw new ProviderFailure(ProviderFailureKind.INVALID_RESPONSE, null);
        }
        Map<String, ProviderToolDefinition> definitions = new HashMap<>();
        request.tools().forEach(tool -> definitions.put(tool.name(), tool));
        ArrayList<ProviderChatMessage.ToolCall> normalized = new ArrayList<>();
        for (int index = 0; index < nativeCalls.size(); index++) {
            AssistantMessage.ToolCall nativeCall = nativeCalls.get(index);
            if (nativeCall == null || !"function".equals(nativeCall.type())) {
                throw new ProviderFailure(ProviderFailureKind.INVALID_RESPONSE, null);
            }
            ProviderToolDefinition definition = definitions.get(nativeCall.name());
            if (definition == null) {
                throw new ProviderFailure(ProviderFailureKind.INVALID_RESPONSE, null);
            }
            String arguments;
            try {
                arguments = definition.normalizeArguments(nativeCall.arguments());
            }
            catch (IllegalArgumentException failure) {
                throw new ProviderFailure(ProviderFailureKind.INVALID_RESPONSE, null);
            }
            normalized.add(new ProviderChatMessage.ToolCall(
                    toolCallId(
                            request, assistantContent, index, definition.name(), arguments),
                    definition.name(),
                    arguments));
        }
        return List.copyOf(normalized);
    }

    private static String toolCallId(
            ProviderChatRequest request,
            String assistantContent,
            int index,
            String name,
            String arguments) {
        String correlation = correlationId(
                request,
                request.providerMessages().size(),
                assistantContent,
                index,
                name,
                arguments);
        return UUID.nameUUIDFromBytes(correlation.getBytes(StandardCharsets.US_ASCII)).toString();
    }

    private static String correlationId(
            ProviderChatRequest request,
            int messageCount,
            String assistantContent,
            int index,
            String name,
            String arguments) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, request.provider().name());
            update(digest, request.modelId());
            List<ProviderChatMessage> messages = request.providerMessages();
            if (messageCount < 0 || messageCount > messages.size()) {
                throw new IllegalArgumentException("Tool call message correlation is invalid.");
            }
            for (int messageIndex = 0; messageIndex < messageCount; messageIndex++) {
                ProviderChatMessage message = messages.get(messageIndex);
                update(digest, message.role().name());
                update(digest, message.content());
                update(digest, message.toolCallId());
                update(digest, message.toolName());
                for (ProviderChatMessage.ToolCall call : message.toolCalls()) {
                    update(digest, call.id());
                    update(digest, call.name());
                    update(digest, call.arguments());
                }
            }
            update(digest, assistantContent);
            update(digest, Integer.toString(index));
            update(digest, name);
            update(digest, arguments);
            return java.util.HexFormat.of().formatHex(digest.digest());
        }
        catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable.", failure);
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }

    private void storeThoughtSignatures(
            ProviderChatRequest request,
            AssistantMessage output,
            List<ProviderChatMessage.ToolCall> normalizedCalls) {
        if (request.provider() != ModelProvider.GOOGLE_GENAI
                || normalizedCalls.isEmpty()
                || output.getMetadata() == null
                || !output.getMetadata().containsKey("thoughtSignatures")) {
            return;
        }
        Object raw = output.getMetadata().get("thoughtSignatures");
        if (!(raw instanceof List<?> values) || values.size() != normalizedCalls.size()) {
            throw new ProviderFailure(ProviderFailureKind.INVALID_RESPONSE, null);
        }
        List<byte[]> validated = new ArrayList<>();
        for (Object item : values) {
            if (!(item instanceof byte[] signature)
                    || signature.length == 0
                    || signature.length > MAX_THOUGHT_SIGNATURE_BYTES) {
                throw new ProviderFailure(ProviderFailureKind.INVALID_RESPONSE, null);
            }
            validated.add(Arrays.copyOf(signature, signature.length));
        }
        Instant expiresAt = clock.instant().plus(THOUGHT_SIGNATURE_TTL);
        String assistantContent = output.getText() == null ? "" : output.getText();
        synchronized (thoughtSignatures) {
            for (int index = 0; index < validated.size(); index++) {
                ProviderChatMessage.ToolCall call = normalizedCalls.get(index);
                thoughtSignatures.put(
                        new ThoughtSignatureKey(
                                request.provider(), request.modelId(),
                                correlationId(
                                        request,
                                        request.providerMessages().size(),
                                        assistantContent,
                                        index,
                                        call.name(),
                                        call.arguments())),
                        new StoredThoughtSignature(
                                validated.get(index), expiresAt));
            }
        }
    }

    private List<byte[]> restoreThoughtSignatures(
            ProviderChatRequest request,
            ProviderChatMessage message,
            int messageIndex) {
        List<ProviderChatMessage.ToolCall> calls = message.toolCalls();
        if (request.provider() != ModelProvider.GOOGLE_GENAI || calls.isEmpty()) {
            return List.of();
        }
        List<byte[]> restored = new ArrayList<>();
        synchronized (thoughtSignatures) {
            for (int callIndex = 0; callIndex < calls.size(); callIndex++) {
                ProviderChatMessage.ToolCall call = calls.get(callIndex);
                ThoughtSignatureKey key = new ThoughtSignatureKey(
                        request.provider(), request.modelId(),
                        correlationId(
                                request,
                                messageIndex,
                                message.content(),
                                callIndex,
                                call.name(),
                                call.arguments()));
                StoredThoughtSignature stored = thoughtSignatures.get(key);
                if (stored == null || !clock.instant().isBefore(stored.expiresAt())) {
                    thoughtSignatures.remove(key);
                    return List.of();
                }
                restored.add(Arrays.copyOf(stored.value(), stored.value().length));
            }
        }
        return List.copyOf(restored);
    }

    private static String legacyEnvelope(
            String content, List<ProviderChatMessage.ToolCall> toolCalls) {
        ObjectNode envelope = JSON.createObjectNode();
        envelope.put("assistant", content);
        ArrayNode calls = envelope.putArray("toolCalls");
        for (ProviderChatMessage.ToolCall call : toolCalls) {
            ObjectNode value = calls.addObject();
            value.put("name", call.name());
            try {
                value.set("arguments", JSON.readTree(call.arguments()));
            }
            catch (JsonProcessingException failure) {
                throw new ProviderFailure(ProviderFailureKind.INVALID_RESPONSE, null);
            }
        }
        try {
            return JSON.writeValueAsString(envelope);
        }
        catch (JsonProcessingException failure) {
            throw new ProviderFailure(ProviderFailureKind.INVALID_RESPONSE, null);
        }
    }

    private static int nonNegative(Integer value) {
        return value == null || value < 0 ? 0 : value;
    }

    private record ThoughtSignatureKey(
            ModelProvider provider, String modelId, String correlationId) { }

    private record StoredThoughtSignature(byte[] value, Instant expiresAt) { }
}

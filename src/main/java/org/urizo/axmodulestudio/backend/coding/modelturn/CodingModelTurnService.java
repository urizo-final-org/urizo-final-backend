package org.urizo.axmodulestudio.backend.coding.modelturn;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.urizo.axmodulestudio.backend.ai.gateway.ModelGatewayErrorCode;
import org.urizo.axmodulestudio.backend.ai.gateway.ModelProvider;
import org.urizo.axmodulestudio.backend.ai.gateway.ModelUseCase;
import org.urizo.axmodulestudio.backend.ai.gateway.ProviderCapabilityRegistry;
import org.urizo.axmodulestudio.backend.ai.gateway.ProviderChatGatewayPort;
import org.urizo.axmodulestudio.backend.ai.gateway.ProviderChatRequest;
import org.urizo.axmodulestudio.backend.ai.gateway.ProviderChatResponse;
import org.urizo.axmodulestudio.backend.ai.gateway.ProviderGatewayException;
import org.urizo.axmodulestudio.backend.ai.gateway.ProviderModelRegistration;

@Service
@ConditionalOnProperty(prefix = "ax.coding.model-turn-bridge", name = "enabled", havingValue = "true")
public class CodingModelTurnService {

    private static final Set<String> CHAT_ONLY = Set.of("CHAT");
    private static final Set<String> CHAT_WITH_TOOLS = Set.of("CHAT", "TOOL_CALLING");
    private static final String LOCAL_TOOL_NAME = "read_file";
    private static final String LOCAL_TOOL_PATH = "README.md";
    private static final String LOCAL_TOOL_SCHEMA_DIGEST =
            "sha256:39b714704935190561ed407980480b9a4a0b346b97346e0bff71fb9ace820194";

    private final ProviderCapabilityRegistry capabilityRegistry;
    private final ProviderChatGatewayPort chatGateway;
    private final Clock clock;
    private final boolean localMockToolCandidateEnabled;

    public CodingModelTurnService(
            ProviderCapabilityRegistry capabilityRegistry,
            ProviderChatGatewayPort chatGateway,
            Clock clock,
            @Value("${ax.coding.model-turn-bridge.local-mock-tool-candidate-enabled:false}")
            boolean localMockToolCandidateEnabled) {
        this.capabilityRegistry = Objects.requireNonNull(capabilityRegistry, "capabilityRegistry is required");
        this.chatGateway = Objects.requireNonNull(chatGateway, "chatGateway is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
        this.localMockToolCandidateEnabled = localMockToolCandidateEnabled;
    }

    public CodingModelTurnContract.Response execute(CodingModelTurnContract.Request request) {
        Objects.requireNonNull(request, "request is required");
        boolean toolCandidateRequested = requireLocalChatSubset(request);
        ProviderModelRegistration selected = capabilityRegistry.candidates(ModelUseCase.CHAT).stream()
                .findFirst()
                .orElseThrow(() -> new ProviderGatewayException(
                        ModelGatewayErrorCode.MODEL_NOT_CONFIGURED,
                        "No configured model can satisfy the requested capability."));
        ProviderChatResponse providerResponse = chatGateway.chat(new ProviderChatRequest(
                selected.provider(),
                selected.modelId(),
                normalizedPrompt(request.messages()),
                request.deadlineAt()));
        if (providerResponse.provider() != selected.provider()
                || !providerResponse.modelId().equals(selected.modelId())
                || providerResponse.content().length() > 200_000
                || providerResponse.inputTokens() > Integer.MAX_VALUE - providerResponse.outputTokens()) {
            throw new ProviderGatewayException(
                    ModelGatewayErrorCode.MODEL_RESPONSE_INVALID,
                    "Model provider returned an invalid normalized response.");
        }
        int totalTokens = providerResponse.inputTokens() + providerResponse.outputTokens();
        List<CodingModelTurnContract.ToolCall> toolCalls = toolCandidateRequested
                ? List.of(new CodingModelTurnContract.ToolCall(
                        UUID.nameUUIDFromBytes((request.turnId() + ":" + LOCAL_TOOL_NAME)
                                .getBytes(StandardCharsets.UTF_8)),
                        LOCAL_TOOL_NAME,
                        JsonNodeFactory.instance.objectNode().put("path", LOCAL_TOOL_PATH)))
                : List.of();
        return new CodingModelTurnContract.Response(
                CodingModelTurnContract.SCHEMA_VERSION,
                request.turnId(),
                request.jobId(),
                request.traceId(),
                request.idempotencyKey(),
                new CodingModelTurnContract.Assistant("assistant", providerResponse.content()),
                toolCalls,
                CodingModelTurnContract.TextResponseFormat.text(),
                new CodingModelTurnContract.SelectedModel(
                        contractProvider(providerResponse.provider()),
                        providerResponse.modelId()),
                new CodingModelTurnContract.TokenUsage(
                        providerResponse.inputTokens(),
                        providerResponse.outputTokens(),
                        totalTokens),
                providerResponse.latency().toMillis(),
                toolCandidateRequested ? "TOOL_CALLS" : "STOP",
                clock.instant());
    }

    private boolean requireLocalChatSubset(CodingModelTurnContract.Request request) {
        JsonNode responseFormat = request.responseFormat();
        boolean textFormat = responseFormat.isObject()
                && responseFormat.size() == 1
                && responseFormat.path("type").isTextual()
                && "TEXT".equals(responseFormat.path("type").textValue());
        Set<String> capabilities = Set.copyOf(request.requiredCapabilities());
        boolean chatOnly = capabilities.equals(CHAT_ONLY) && request.toolSchemas().isEmpty();
        boolean localToolCandidate = localMockToolCandidateEnabled
                && capabilities.equals(CHAT_WITH_TOOLS)
                && validLocalToolSchema(request.toolSchemas());
        if ((!chatOnly && !localToolCandidate) || !textFormat) {
            throw new ProviderGatewayException(
                    ModelGatewayErrorCode.MODEL_CAPABILITY_UNSUPPORTED,
                    "The local Model Turn bridge cannot satisfy the requested capability set.");
        }
        return localToolCandidate;
    }

    private static boolean validLocalToolSchema(List<JsonNode> toolSchemas) {
        if (toolSchemas.size() != 1) {
            return false;
        }
        JsonNode toolSchema = toolSchemas.get(0);
        JsonNode input = toolSchema.path("inputSchema");
        JsonNode required = input.path("required");
        JsonNode properties = input.path("properties");
        JsonNode path = properties.path("path");
        return LOCAL_TOOL_NAME.equals(toolSchema.path("name").textValue())
                && LOCAL_TOOL_SCHEMA_DIGEST.equals(toolSchema.path("schemaDigest").textValue())
                && input.isObject() && input.size() == 4
                && "object".equals(input.path("type").textValue())
                && input.path("additionalProperties").isBoolean()
                && !input.path("additionalProperties").booleanValue()
                && required.isArray() && required.size() == 1
                && "path".equals(required.get(0).textValue())
                && properties.isObject() && properties.size() == 1
                && path.isObject() && path.size() == 1
                && "string".equals(path.path("type").textValue());
    }

    private static String normalizedPrompt(List<JsonNode> messages) {
        StringBuilder prompt = new StringBuilder();
        for (JsonNode message : messages) {
            if (!message.isObject() || message.size() != 2
                    || !message.path("role").isTextual()
                    || !message.path("content").isTextual()) {
                throw new ProviderGatewayException(
                        ModelGatewayErrorCode.CONTRACT_VALIDATION_FAILED,
                        "CHAT messages must contain exactly role and content.");
            }
            String role = message.path("role").textValue();
            String content = message.path("content").textValue();
            if (!Set.of("system", "user", "assistant").contains(role)
                    || content.isBlank() || content.length() > 200_000) {
                throw new ProviderGatewayException(
                        ModelGatewayErrorCode.CONTRACT_VALIDATION_FAILED,
                        "CHAT message role or content is invalid.");
            }
            prompt.append('[').append(role).append("]\n").append(content).append("\n\n");
        }
        return prompt.toString().stripTrailing();
    }

    private static String contractProvider(ModelProvider provider) {
        return switch (provider) {
            case OPENAI -> "OPENAI";
            case ANTHROPIC -> "ANTHROPIC";
            case GOOGLE_GENAI, VERTEX_AI_GEMINI -> "GOOGLE";
        };
    }
}

package org.urizo.axmodulestudio.backend.coding.service;

import org.urizo.axmodulestudio.backend.coding.dto.CodingModelTurnContract;
import org.urizo.axmodulestudio.backend.cms.assistant.NaturalCmsToolContract;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ModelGatewayErrorCode;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ModelProvider;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ModelUseCase;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderCapabilityRegistry;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderChatGatewayPort;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderChatRequest;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderChatResponse;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderGatewayException;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderModelRegistration;

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
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final boolean localMockToolCandidateEnabled;

    public CodingModelTurnService(
            ProviderCapabilityRegistry capabilityRegistry,
            ProviderChatGatewayPort chatGateway,
            ObjectMapper objectMapper,
            Clock clock,
            @Value("${ax.coding.model-turn-bridge.local-mock-tool-candidate-enabled:false}")
            boolean localMockToolCandidateEnabled) {
        this.capabilityRegistry = Objects.requireNonNull(capabilityRegistry, "capabilityRegistry is required");
        this.chatGateway = Objects.requireNonNull(chatGateway, "chatGateway is required");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
        this.localMockToolCandidateEnabled = localMockToolCandidateEnabled;
    }

    public CodingModelTurnContract.Response execute(CodingModelTurnContract.Request request) {
        return execute(request, false);
    }

    public CodingModelTurnContract.Response executeNaturalCms(
            CodingModelTurnContract.Request request) {
        return execute(request, true);
    }

    private CodingModelTurnContract.Response execute(
            CodingModelTurnContract.Request request, boolean naturalCms) {
        Objects.requireNonNull(request, "request is required");
        ToolMode toolMode = requireSupportedSubset(request, naturalCms);
        ModelUseCase useCase = toolMode == ToolMode.PROVIDER
                ? ModelUseCase.TOOL_CALL : ModelUseCase.CHAT;
        ProviderModelRegistration selected = capabilityRegistry.candidates(useCase).stream()
                .findFirst()
                .orElseThrow(() -> new ProviderGatewayException(
                        ModelGatewayErrorCode.MODEL_NOT_CONFIGURED,
                        "No configured model can satisfy the requested capability."));
        ProviderChatResponse providerResponse = chatGateway.chat(new ProviderChatRequest(
                selected.provider(),
                selected.modelId(),
                normalizedPrompt(request, toolMode),
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
        ParsedAssistant parsed = toolMode == ToolMode.PROVIDER
                ? parseProviderToolEnvelope(request, providerResponse.content())
                : new ParsedAssistant(providerResponse.content(), List.of());
        List<CodingModelTurnContract.ToolCall> toolCalls = toolMode == ToolMode.LOCAL_FIXTURE
                ? List.of(new CodingModelTurnContract.ToolCall(
                        UUID.nameUUIDFromBytes((request.turnId() + ":" + LOCAL_TOOL_NAME)
                                .getBytes(StandardCharsets.UTF_8)),
                        LOCAL_TOOL_NAME,
                        JsonNodeFactory.instance.objectNode().put("path", LOCAL_TOOL_PATH)))
                : parsed.toolCalls();
        return new CodingModelTurnContract.Response(
                CodingModelTurnContract.SCHEMA_VERSION,
                request.turnId(),
                request.jobId(),
                request.traceId(),
                request.idempotencyKey(),
                new CodingModelTurnContract.Assistant("assistant", parsed.content()),
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
                toolCalls.isEmpty() ? "STOP" : "TOOL_CALLS",
                clock.instant());
    }

    private ToolMode requireSupportedSubset(
            CodingModelTurnContract.Request request, boolean naturalCms) {
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
        boolean providerToolCandidate = capabilities.equals(CHAT_WITH_TOOLS)
                && validApprovedToolSchemas(request.toolSchemas(), naturalCms);
        if ((!chatOnly && !localToolCandidate && !providerToolCandidate) || !textFormat) {
            throw new ProviderGatewayException(
                    ModelGatewayErrorCode.MODEL_CAPABILITY_UNSUPPORTED,
                    "The local Model Turn bridge cannot satisfy the requested capability set.");
        }
        if (localToolCandidate) {
            return ToolMode.LOCAL_FIXTURE;
        }
        return providerToolCandidate ? ToolMode.PROVIDER : ToolMode.NONE;
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

    private static boolean validApprovedToolSchemas(
            List<JsonNode> toolSchemas, boolean naturalCms) {
        if (toolSchemas.isEmpty()) {
            return false;
        }
        Set<String> names = new HashSet<>();
        for (JsonNode toolSchema : toolSchemas) {
            String name = toolSchema.path("name").asText();
            String expectedDigest = naturalCms
                    ? NaturalCmsToolContract.MODEL_TOOL_SCHEMA_DIGESTS.get(name)
                    : CodingToolService.CODING_TOOL_SCHEMA_DIGESTS.get(name);
            if (expectedDigest == null
                    || !expectedDigest.equals(toolSchema.path("schemaDigest").asText())
                    || !names.add(name)) {
                return false;
            }
        }
        return true;
    }

    private String normalizedPrompt(CodingModelTurnContract.Request request, ToolMode toolMode) {
        StringBuilder prompt = new StringBuilder();
        for (JsonNode message : request.messages()) {
            String role = message.path("role").textValue();
            String content;
            if ("tool".equals(role) || message.has("toolCalls")) {
                try {
                    content = objectMapper.writeValueAsString(message);
                }
                catch (JsonProcessingException failure) {
                    throw invalidContract();
                }
            }
            else {
                content = message.path("content").textValue();
            }
            prompt.append('[').append(role).append("]\n").append(content).append("\n\n");
        }
        if (toolMode == ToolMode.PROVIDER) {
            try {
                prompt.append("[system]\n")
                        .append("Return exactly one JSON object with fields assistant and toolCalls. ")
                        .append("assistant must be a string and toolCalls must contain zero or one ")
                        .append("declared tool call with name and arguments. Do not add markdown.\n")
                        .append("Declared tools: ")
                        .append(objectMapper.writeValueAsString(request.toolSchemas()));
            }
            catch (JsonProcessingException failure) {
                throw invalidContract();
            }
        }
        return prompt.toString().stripTrailing();
    }

    private ParsedAssistant parseProviderToolEnvelope(
            CodingModelTurnContract.Request request, String content) {
        try {
            JsonNode envelope = objectMapper.readTree(content);
            if (!envelope.isObject()
                    || envelope.size() != 2
                    || !envelope.path("assistant").isTextual()
                    || !envelope.path("toolCalls").isArray()
                    || envelope.path("toolCalls").size() > 1) {
                throw new IllegalArgumentException();
            }
            Map<String, JsonNode> declared = new HashMap<>();
            for (JsonNode schema : request.toolSchemas()) {
                declared.put(schema.path("name").asText(), schema.path("inputSchema"));
            }
            List<CodingModelTurnContract.ToolCall> calls = new ArrayList<>();
            for (JsonNode call : envelope.path("toolCalls")) {
                if (!call.isObject() || call.size() != 2
                        || !call.path("name").isTextual()
                        || !call.path("arguments").isObject()) {
                    throw new IllegalArgumentException();
                }
                String name = call.path("name").asText();
                JsonNode schema = declared.get(name);
                if (schema == null || !matchesInputSchema(call.path("arguments"), schema)) {
                    throw new IllegalArgumentException();
                }
                byte[] identity = objectMapper.writeValueAsBytes(List.of(
                        request.turnId().toString(), name, call.path("arguments")));
                calls.add(new CodingModelTurnContract.ToolCall(
                        UUID.nameUUIDFromBytes(identity), name,
                        call.path("arguments").deepCopy()));
            }
            String assistant = envelope.path("assistant").asText();
            if (assistant.isBlank() && calls.isEmpty()) {
                throw new IllegalArgumentException();
            }
            return new ParsedAssistant(assistant, List.copyOf(calls));
        }
        catch (JsonProcessingException | IllegalArgumentException failure) {
            throw new ProviderGatewayException(
                    ModelGatewayErrorCode.MODEL_RESPONSE_INVALID,
                    "Model provider returned an invalid Coding tool envelope.");
        }
    }

    private static boolean matchesInputSchema(JsonNode arguments, JsonNode schema) {
        if (!schema.isObject()
                || !"object".equals(schema.path("type").asText())
                || !schema.path("additionalProperties").isBoolean()
                || schema.path("additionalProperties").asBoolean()
                || !schema.path("properties").isObject()
                || !schema.path("required").isArray()) {
            return false;
        }
        Set<String> actual = new HashSet<>();
        arguments.fieldNames().forEachRemaining(actual::add);
        Set<String> allowed = new HashSet<>();
        schema.path("properties").fieldNames().forEachRemaining(allowed::add);
        if (!allowed.containsAll(actual)) {
            return false;
        }
        for (JsonNode required : schema.path("required")) {
            if (!required.isTextual() || !actual.contains(required.asText())) {
                return false;
            }
        }
        for (String field : actual) {
            JsonNode definition = schema.path("properties").path(field);
            JsonNode value = arguments.path(field);
            if (("string".equals(definition.path("type").asText()) && !value.isTextual())
                    || ("integer".equals(definition.path("type").asText()) && !value.isInt())) {
                return false;
            }
        }
        return true;
    }

    private static ProviderGatewayException invalidContract() {
        return new ProviderGatewayException(
                ModelGatewayErrorCode.CONTRACT_VALIDATION_FAILED,
                "Coding Model Turn messages could not be normalized.");
    }

    private static String contractProvider(ModelProvider provider) {
        return switch (provider) {
            case OPENAI -> "OPENAI";
            case ANTHROPIC -> "ANTHROPIC";
            case GOOGLE_GENAI, VERTEX_AI_GEMINI -> "GOOGLE";
        };
    }

    private enum ToolMode { NONE, LOCAL_FIXTURE, PROVIDER }

    private record ParsedAssistant(
            String content, List<CodingModelTurnContract.ToolCall> toolCalls) { }
}

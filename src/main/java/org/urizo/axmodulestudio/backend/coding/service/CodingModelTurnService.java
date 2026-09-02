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
import org.urizo.axmodulestudio.backend.integration.ai.gateway.CapabilityRegistrationException;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ModelGatewayErrorCode;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ModelProvider;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ModelUseCase;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderCapabilityRegistry;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderChatGatewayPort;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderChatMessage;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderChatRequest;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderChatResponse;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderGatewayException;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderModelRegistration;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderResponseFormat;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderToolDefinition;

@Service
@ConditionalOnProperty(prefix = "ax.coding.model-turn-bridge", name = "enabled", havingValue = "true")
public class CodingModelTurnService {

    private static final Set<String> CHAT_ONLY = Set.of("CHAT");
    private static final Set<String> CHAT_WITH_TOOLS = Set.of("CHAT", "TOOL_CALLING");
    private static final String LOCAL_TOOL_NAME = "read_file";
    private static final String LOCAL_TOOL_PATH = "README.md";
    private static final String LOCAL_TOOL_SCHEMA_DIGEST =
            "sha256:39b714704935190561ed407980480b9a4a0b346b97346e0bff71fb9ace820194";
    /** Mirrors the ProviderChatRequest budget so an oversized context fails as a gateway error. */
    private static final int MAX_REQUEST_CHARACTERS = 65_536;
    // A JSON object because a native tool response body must parse as JSON.
    private static final String ELIDED_TOOL_CONTENT =
            "{\"content\":\"[elided for the request budget. "
                    + "Re-read the file when its body is required.]\"}";
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
        return execute(request, false, null);
    }

    public CodingModelTurnContract.Response execute(
            CodingModelTurnContract.Request request,
            List<ProviderModelRegistration> boundModels) {
        return execute(request, false, List.copyOf(boundModels));
    }

    public CodingModelTurnContract.Response executeNaturalCms(
            CodingModelTurnContract.Request request) {
        return execute(request, true, null);
    }

    public CodingModelTurnContract.Response executeNaturalCms(
            CodingModelTurnContract.Request request,
            List<ProviderModelRegistration> boundModels) {
        return execute(request, true, List.copyOf(boundModels));
    }

    private CodingModelTurnContract.Response execute(
            CodingModelTurnContract.Request request,
            boolean naturalCms,
            List<ProviderModelRegistration> boundModels) {
        Objects.requireNonNull(request, "request is required");
        ToolMode toolMode = requireSupportedSubset(request, naturalCms);
        ModelUseCase useCase = switch (toolMode) {
            case PROVIDER -> ModelUseCase.TOOL_CALL;
            case STRUCTURED -> ModelUseCase.STRUCTURED_OUTPUT;
            case NONE, LOCAL_FIXTURE -> ModelUseCase.CHAT;
        };
        List<ProviderModelRegistration> candidates = modelCandidates(boundModels, useCase);
        ProviderGatewayException lastFailure = null;
        for (ProviderModelRegistration selected : candidates) {
            try {
                return executeSelected(request, toolMode, selected);
            }
            catch (ProviderGatewayException failure) {
                lastFailure = failure;
                if (!fallbackEligible(failure.code())) {
                    throw failure;
                }
            }
        }
        if (lastFailure != null) {
            throw lastFailure;
        }
        throw new ProviderGatewayException(
                ModelGatewayErrorCode.MODEL_NOT_CONFIGURED,
                "No configured model can satisfy the requested capability.");
    }

    private List<ProviderModelRegistration> modelCandidates(
            List<ProviderModelRegistration> boundModels,
            ModelUseCase useCase) {
        if (boundModels == null) {
            return capabilityRegistry.candidates(useCase).stream().limit(1).toList();
        }
        if (boundModels.isEmpty()) {
            return List.of();
        }
        List<ProviderModelRegistration> validated = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ProviderModelRegistration candidate : boundModels) {
            if (candidate == null
                    || !seen.add(candidate.provider().name() + ":" + candidate.modelId())) {
                throw new ProviderGatewayException(
                        ModelGatewayErrorCode.MODEL_NOT_CONFIGURED,
                        "The bound model selection is invalid.");
            }
            try {
                validated.add(capabilityRegistry.require(
                        candidate.provider(), candidate.modelId(), useCase));
            }
            catch (CapabilityRegistrationException failure) {
                throw new ProviderGatewayException(failure.code(),
                        "The bound model cannot satisfy the requested capability.");
            }
        }
        return List.copyOf(validated);
    }

    private static boolean fallbackEligible(ModelGatewayErrorCode code) {
        return Set.of(
                ModelGatewayErrorCode.MODEL_NOT_CONFIGURED,
                ModelGatewayErrorCode.MODEL_RATE_LIMITED,
                ModelGatewayErrorCode.MODEL_TIMEOUT,
                ModelGatewayErrorCode.MODEL_PROVIDER_UNAVAILABLE,
                ModelGatewayErrorCode.INTERNAL_TRANSIENT_ERROR).contains(code);
    }

    private CodingModelTurnContract.Response executeSelected(
            CodingModelTurnContract.Request request,
            ToolMode toolMode,
            ProviderModelRegistration selected) {
        ProviderResponseFormat responseFormat = providerResponseFormat(request);
        List<ProviderToolDefinition> providerTools = toolMode == ToolMode.PROVIDER
                ? providerTools(request) : List.of();
        ProviderChatResponse providerResponse = chatGateway.chat(new ProviderChatRequest(
                selected.provider(),
                selected.modelId(),
                normalizedMessages(request, requestOverhead(providerTools, responseFormat)),
                providerTools,
                responseFormat,
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
        if (toolMode != ToolMode.PROVIDER && !providerResponse.toolCalls().isEmpty()) {
            throw invalidProviderResponse();
        }
        List<CodingModelTurnContract.ToolCall> nativeCalls = toolMode == ToolMode.PROVIDER
                ? nativeToolCalls(providerResponse, providerTools)
                : List.of();
        List<CodingModelTurnContract.ToolCall> toolCalls = toolMode == ToolMode.LOCAL_FIXTURE
                ? List.of(new CodingModelTurnContract.ToolCall(
                        UUID.nameUUIDFromBytes((request.turnId() + ":" + LOCAL_TOOL_NAME)
                                .getBytes(StandardCharsets.UTF_8)),
                        LOCAL_TOOL_NAME,
                        JsonNodeFactory.instance.objectNode().put("path", LOCAL_TOOL_PATH)))
                : nativeCalls;
        JsonNode structuredOutput = toolMode == ToolMode.STRUCTURED
                ? responseFormat.validateOrRepair(providerResponse.content()) : null;
        String assistantContent = structuredOutput == null
                ? providerResponse.content() : "";
        CodingModelTurnContract.ResponseFormat resultFormat = structuredOutput == null
                ? CodingModelTurnContract.TextResponseFormat.text()
                : new CodingModelTurnContract.JsonSchemaResponseFormat(
                        "JSON_SCHEMA", responseFormat.schemaDigest(), structuredOutput);
        return new CodingModelTurnContract.Response(
                CodingModelTurnContract.SCHEMA_VERSION,
                request.turnId(),
                request.jobId(),
                request.traceId(),
                request.idempotencyKey(),
                new CodingModelTurnContract.Assistant("assistant", assistantContent),
                toolCalls,
                resultFormat,
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
        ProviderResponseFormat responseFormat = providerResponseFormat(request);
        Set<String> capabilities = Set.copyOf(request.requiredCapabilities());
        boolean chatOnly = capabilities.equals(CHAT_ONLY)
                && request.toolSchemas().isEmpty()
                && !responseFormat.structured();
        boolean structured = capabilities.equals(Set.of("CHAT", "STRUCTURED_OUTPUT"))
                && request.toolSchemas().isEmpty()
                && responseFormat.structured();
        boolean localToolCandidate = localMockToolCandidateEnabled
                && capabilities.equals(CHAT_WITH_TOOLS)
                && validLocalToolSchema(request.toolSchemas());
        boolean providerToolCandidate = capabilities.equals(CHAT_WITH_TOOLS)
                && validApprovedToolSchemas(request.toolSchemas(), naturalCms);
        if (!chatOnly && !structured && !localToolCandidate && !providerToolCandidate) {
            throw new ProviderGatewayException(
                    ModelGatewayErrorCode.MODEL_CAPABILITY_UNSUPPORTED,
                    "The local Model Turn bridge cannot satisfy the requested capability set.");
        }
        if (localToolCandidate) {
            return ToolMode.LOCAL_FIXTURE;
        }
        if (structured) {
            return ToolMode.STRUCTURED;
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

    private List<ProviderChatMessage> normalizedMessages(
            CodingModelTurnContract.Request request, long requestOverhead) {
        List<ProviderChatMessage> messages = new ArrayList<>();
        Map<String, String> pendingToolNames = new HashMap<>();
        for (JsonNode message : request.messages()) {
            String role = message.path("role").textValue();
            String content = message.path("content").textValue();
            try {
                switch (role) {
                    case "system" -> messages.add(ProviderChatMessage.plain(
                            ProviderChatMessage.Role.SYSTEM, content));
                    case "user" -> messages.add(ProviderChatMessage.plain(
                            ProviderChatMessage.Role.USER, content));
                    case "assistant" -> {
                        List<ProviderChatMessage.ToolCall> toolCalls = new ArrayList<>();
                        if (message.has("toolCalls")) {
                            for (JsonNode toolCall : message.path("toolCalls")) {
                                String toolCallId = toolCall.path("toolCallId").asText();
                                String name = toolCall.path("name").asText();
                                if (pendingToolNames.putIfAbsent(toolCallId, name) != null) {
                                    throw invalidContract();
                                }
                                toolCalls.add(new ProviderChatMessage.ToolCall(
                                        toolCallId,
                                        name,
                                        objectMapper.writeValueAsString(
                                                toolCall.path("arguments"))));
                            }
                        }
                        messages.add(ProviderChatMessage.assistant(content, toolCalls));
                    }
                    case "tool" -> {
                        String toolCallId = message.path("toolCallId").asText();
                        String name = pendingToolNames.remove(toolCallId);
                        if (name == null) {
                            throw invalidContract();
                        }
                        messages.add(ProviderChatMessage.tool(
                                toolCallId, name, jsonToolResult(content)));
                    }
                    default -> throw invalidContract();
                }
            }
            catch (JsonProcessingException | IllegalArgumentException failure) {
                throw invalidContract();
            }
        }
        if (!pendingToolNames.isEmpty()) {
            throw invalidContract();
        }
        return withinRequestBudget(messages, requestOverhead);
    }

    /**
     * What ProviderChatRequest counts besides the messages. Tool definitions and a structured
     * output schema ride in the same budget, so the message budget is what is left after them.
     */
    private static long requestOverhead(
            List<ProviderToolDefinition> tools, ProviderResponseFormat responseFormat) {
        return tools.stream()
                .mapToLong(tool -> tool.name().length()
                        + tool.description().length()
                        + tool.providerInputSchema().length())
                .sum()
                + (responseFormat.structured()
                        ? responseFormat.providerOutputSchema().length() : 0);
    }

    /**
     * A native tool response must be JSON: Gemini parses the function response body and
     * rejects the whole request when a text tool result - a read file, for example -
     * does not parse. A result that is already a JSON object or array passes through
     * unchanged; anything else is wrapped as one field.
     */
    private String jsonToolResult(String content) {
        try {
            JsonNode parsed = objectMapper.readTree(content);
            if (parsed != null && (parsed.isObject() || parsed.isArray())) {
                return content;
            }
        }
        catch (JsonProcessingException ignored) {
            // Not JSON - wrapped below.
        }
        return objectMapper.createObjectNode().put("content", content).toString();
    }

    /**
     * A tool message carries the whole tool body, so a handful of file reads alone
     * exceeds the ProviderChatRequest budget and the request would be rejected as a
     * raw argument failure. The oldest tool bodies are dropped first, and only once
     * the request would not fit, so a request that already fits is unchanged.
     *
     * <p>The tool definitions and any structured output schema are reserved first, because
     * ProviderChatRequest counts them in the same budget. Measuring the messages alone left a
     * band where nothing was elided here and the request was still refused on construction.
     */
    private List<ProviderChatMessage> withinRequestBudget(
            List<ProviderChatMessage> messages, long requestOverhead) {
        long messageBudget = MAX_REQUEST_CHARACTERS - requestOverhead;
        List<ProviderChatMessage> bounded = new ArrayList<>(messages);
        long characters = characters(bounded);
        for (int index = 0; index < bounded.size() && characters > messageBudget; index++) {
            ProviderChatMessage message = bounded.get(index);
            if (message.role() != ProviderChatMessage.Role.TOOL
                    || ELIDED_TOOL_CONTENT.equals(message.content())) {
                continue;
            }
            characters -= message.content().length() - ELIDED_TOOL_CONTENT.length();
            bounded.set(index, ProviderChatMessage.tool(
                    message.toolCallId(), message.toolName(), ELIDED_TOOL_CONTENT));
        }
        if (characters > messageBudget) {
            throw new ProviderGatewayException(
                    ModelGatewayErrorCode.MODEL_CAPABILITY_UNSUPPORTED,
                    "The Coding stage context exceeds the model request budget.");
        }
        return List.copyOf(bounded);
    }

    /** Counts what ProviderChatRequest bounds for the messages themselves. */
    private static long characters(List<ProviderChatMessage> messages) {
        return messages.stream()
                .mapToLong(message -> message.content().length()
                        + message.toolCalls().stream()
                                .mapToLong(call -> call.arguments().length())
                                .sum())
                .sum();
    }

    private ProviderResponseFormat providerResponseFormat(
            CodingModelTurnContract.Request request) {
        try {
            return ProviderResponseFormat.fromContract(request.responseFormat());
        }
        catch (IllegalArgumentException failure) {
            throw invalidContract();
        }
    }

    private static List<ProviderToolDefinition> providerTools(
            CodingModelTurnContract.Request request) {
        try {
            return request.toolSchemas().stream()
                    .map(ProviderToolDefinition::fromContract)
                    .toList();
        }
        catch (IllegalArgumentException failure) {
            throw invalidContract();
        }
    }

    private List<CodingModelTurnContract.ToolCall> nativeToolCalls(
            ProviderChatResponse response,
            List<ProviderToolDefinition> definitions) {
        Map<String, ProviderToolDefinition> declared = new HashMap<>();
        definitions.forEach(definition -> declared.put(definition.name(), definition));
        List<CodingModelTurnContract.ToolCall> normalized = new ArrayList<>();
        try {
            for (ProviderChatMessage.ToolCall call : response.toolCalls()) {
                ProviderToolDefinition definition = declared.get(call.name());
                if (definition == null) {
                    throw new IllegalArgumentException();
                }
                String arguments = definition.normalizeArguments(call.arguments());
                JsonNode decoded = objectMapper.readTree(arguments);
                normalized.add(new CodingModelTurnContract.ToolCall(
                        UUID.fromString(call.id()), call.name(), decoded));
            }
            return List.copyOf(normalized);
        }
        catch (JsonProcessingException | IllegalArgumentException failure) {
            throw invalidProviderResponse();
        }
    }

    private static ProviderGatewayException invalidContract() {
        return new ProviderGatewayException(
                ModelGatewayErrorCode.CONTRACT_VALIDATION_FAILED,
                "Coding Model Turn messages could not be normalized.");
    }

    private static ProviderGatewayException invalidProviderResponse() {
        return new ProviderGatewayException(
                ModelGatewayErrorCode.MODEL_RESPONSE_INVALID,
                "Model provider returned an invalid normalized response.");
    }

    private static String contractProvider(ModelProvider provider) {
        return switch (provider) {
            case OPENAI -> "OPENAI";
            case ANTHROPIC -> "ANTHROPIC";
            case GOOGLE_GENAI, VERTEX_AI_GEMINI -> "GOOGLE";
        };
    }

    private enum ToolMode { NONE, LOCAL_FIXTURE, PROVIDER, STRUCTURED }
}

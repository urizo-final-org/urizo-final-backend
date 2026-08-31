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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import org.urizo.axmodulestudio.backend.integration.ai.gateway.StructuredOutputGuard;

@Service
@ConditionalOnProperty(prefix = "ax.coding.model-turn-bridge", name = "enabled", havingValue = "true")
public class CodingModelTurnService {

    /** A reply's own key is recorded only when it is a plain identifier. */
    private static final java.util.regex.Pattern SAFE_DIAGNOSTIC_KEY =
            java.util.regex.Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,39}$");
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
    private static final StructuredOutputGuard STRUCTURED_OUTPUT_GUARD =
            new StructuredOutputGuard();
    private final ProviderCapabilityRegistry capabilityRegistry;
    private final ProviderChatGatewayPort chatGateway;
    private final ObjectMapper objectMapper;
    /**
     * Reads the model's tool envelope only. A patch body carries real line breaks and
     * models regularly emit them unescaped inside the JSON string, which strict JSON
     * rejects wholesale. Everything else about the envelope stays strictly validated.
     */
    private final ObjectMapper toolEnvelopeReader;
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
        this.toolEnvelopeReader = objectMapper.copy().enable(
                com.fasterxml.jackson.core.json.JsonReadFeature
                        .ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature());
        this.clock = Objects.requireNonNull(clock, "clock is required");
        this.localMockToolCandidateEnabled = localMockToolCandidateEnabled;
    }

    public CodingModelTurnContract.Response execute(CodingModelTurnContract.Request request) {
        return execute(request, false, null, node -> { });
    }

    public CodingModelTurnContract.Response execute(
            CodingModelTurnContract.Request request,
            List<ProviderModelRegistration> boundModels) {
        return execute(request, false, List.copyOf(boundModels), node -> { });
    }

    /**
     * @param envelopeDiagnostic receives structural facts when a reply fails the tool
     *     envelope contract, just before the usual gateway failure is thrown. A failed
     *     turn stores no reply, so this is the only record of why a contract miss
     *     happened. The thrown type is unchanged, so callers that do not want the
     *     diagnostic keep their existing behaviour.
     */
    public CodingModelTurnContract.Response execute(
            CodingModelTurnContract.Request request,
            List<ProviderModelRegistration> boundModels,
            java.util.function.Consumer<JsonNode> envelopeDiagnostic) {
        return execute(request, false,
                boundModels == null ? null : List.copyOf(boundModels), envelopeDiagnostic);
    }

    public CodingModelTurnContract.Response executeNaturalCms(
            CodingModelTurnContract.Request request) {
        return execute(request, true, null, node -> { });
    }

    public CodingModelTurnContract.Response executeNaturalCms(
            CodingModelTurnContract.Request request,
            List<ProviderModelRegistration> boundModels) {
        return execute(request, true, List.copyOf(boundModels), node -> { });
    }

    private CodingModelTurnContract.Response execute(
            CodingModelTurnContract.Request request,
            boolean naturalCms,
            List<ProviderModelRegistration> boundModels,
            java.util.function.Consumer<JsonNode> envelopeDiagnostic) {
        Objects.requireNonNull(request, "request is required");
        ToolMode toolMode = requireSupportedSubset(request, naturalCms);
        ModelUseCase useCase = toolMode == ToolMode.PROVIDER
                ? ModelUseCase.TOOL_CALL : ModelUseCase.CHAT;
        List<ProviderModelRegistration> candidates = modelCandidates(boundModels, useCase);
        ProviderGatewayException lastFailure = null;
        for (ProviderModelRegistration selected : candidates) {
            try {
                return executeSelected(request, toolMode, selected, envelopeDiagnostic);
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
            ProviderModelRegistration selected,
            java.util.function.Consumer<JsonNode> envelopeDiagnostic) {
        // First layer of the JSON defence. Every CHAT caller here parses the reply as
        // one JSON object, so ask the provider for that shape directly instead of only
        // requesting it in the prompt. A TOOL_CALL request keeps its own reply shape,
        // which is why the two never share a request mode.
        ProviderChatResponse providerResponse = chatGateway.chat(new ProviderChatRequest(
                selected.provider(),
                selected.modelId(),
                normalizedMessages(request, toolMode),
                request.deadlineAt(),
                toolMode != ToolMode.PROVIDER));
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
                ? parseProviderToolEnvelope(request, providerResponse.content(), envelopeDiagnostic)
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

    private List<ProviderChatMessage> normalizedMessages(
            CodingModelTurnContract.Request request, ToolMode toolMode) {
        List<ProviderChatMessage> messages = new ArrayList<>();
        Map<String, String> pendingToolNames = new HashMap<>();
        if (toolMode == ToolMode.PROVIDER) {
            try {
                // Byte-identical to the native conversion trigger: ProviderChatRequest
                // recognizes this prefix, declares the tools natively to the provider,
                // and strips this message. The model no longer hand-writes the envelope
                // for tool calls, and the reply is converted back into this envelope.
                messages.add(ProviderChatMessage.plain(
                        ProviderChatMessage.Role.SYSTEM,
                        org.urizo.axmodulestudio.backend.integration.ai.gateway
                                .ProviderToolDefinition.LEGACY_TOOL_PROMPT_PREFIX
                                + objectMapper.writeValueAsString(request.toolSchemas())));
            }
            catch (JsonProcessingException failure) {
                throw invalidContract();
            }
        }
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
                                toolCallId, name,
                                toolMode == ToolMode.PROVIDER
                                        ? jsonToolResult(content) : content));
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
        if (toolMode == ToolMode.PROVIDER) {
            // The native tool path owns this list as-is: the trigger message must stay
            // first and alone (it is stripped after conversion), and the tool history
            // replays natively through the adapter. Folding or flattening here would
            // break that contract; after the strip exactly one system message remains.
            return withinRequestBudget(messages, 0);
        }
        // A re-asked or plain CHAT turn replays any tool exchange as text, because no
        // tool is declared on that request and an undeclared native exchange makes
        // Gemini return no candidate at all. The budget runs first so it can still see
        // the tool bodies it is allowed to drop; flattening adds a fixed wrapper per
        // message, so that exact cost is measured once and reserved up front.
        long flatteningCost = characters(flattenToolProtocol(messages)) - characters(messages);
        return foldSystemMessages(
                flattenToolProtocol(withinRequestBudget(messages, flatteningCost)));
    }

    /**
     * The tool protocol on this path is written into the prompt, so no tool is ever
     * declared to the provider. A later turn that replays the exchange as a native
     * assistant tool call and tool response therefore describes tools the request does
     * not have, and Gemini answers with no candidate at all. The exchange is replayed
     * as plain text instead, in the same envelope shape the model was asked to produce.
     */
    private List<ProviderChatMessage> flattenToolProtocol(List<ProviderChatMessage> messages) {
        List<ProviderChatMessage> flattened = new ArrayList<>();
        for (ProviderChatMessage message : messages) {
            switch (message.role()) {
                case ASSISTANT -> flattened.add(message.toolCalls().isEmpty()
                        ? message
                        : ProviderChatMessage.plain(
                                ProviderChatMessage.Role.ASSISTANT,
                                assistantEnvelopeText(message)));
                case TOOL -> flattened.add(ProviderChatMessage.plain(
                        ProviderChatMessage.Role.USER,
                        "Result of " + message.toolName() + ": " + message.content()));
                default -> flattened.add(message);
            }
        }
        return flattened;
    }

    private String assistantEnvelopeText(ProviderChatMessage message) {
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("assistant", message.content());
        ArrayNode calls = envelope.putArray("toolCalls");
        for (ProviderChatMessage.ToolCall call : message.toolCalls()) {
            ObjectNode entry = calls.addObject();
            entry.put("name", call.name());
            try {
                entry.set("arguments", objectMapper.readTree(call.arguments()));
            }
            catch (JsonProcessingException failure) {
                throw invalidContract();
            }
        }
        return envelope.toString();
    }

    /**
     * Gemini carries a single system instruction and Spring AI rejects a prompt that holds
     * more than one system message, so the tool envelope instruction and the stage system
     * prompt are folded into one. Every system message on this path is built before the
     * first user turn, so folding keeps the order the model sees.
     */
    private static List<ProviderChatMessage> foldSystemMessages(List<ProviderChatMessage> messages) {
        long systemMessages = messages.stream()
                .filter(message -> message.role() == ProviderChatMessage.Role.SYSTEM)
                .count();
        if (systemMessages <= 1) {
            return messages;
        }
        StringBuilder merged = new StringBuilder();
        List<ProviderChatMessage> remainder = new ArrayList<>();
        for (ProviderChatMessage message : messages) {
            if (message.role() == ProviderChatMessage.Role.SYSTEM) {
                if (!merged.isEmpty()) {
                    merged.append("\n\n");
                }
                merged.append(message.content());
            }
            else {
                remainder.add(message);
            }
        }
        List<ProviderChatMessage> folded = new ArrayList<>();
        folded.add(ProviderChatMessage.plain(
                ProviderChatMessage.Role.SYSTEM, merged.toString()));
        folded.addAll(remainder);
        return folded;
    }

    /**
     * A tool message carries the whole tool body, so a handful of file reads alone
     * exceeds the ProviderChatRequest budget and the request would be rejected as a
     * raw argument failure. The oldest tool bodies are dropped first, and only once
     * the request would not fit, so a request that already fits is unchanged.
     */
    private List<ProviderChatMessage> withinRequestBudget(
            List<ProviderChatMessage> messages, long reserved) {
        long budget = MAX_REQUEST_CHARACTERS - reserved;
        List<ProviderChatMessage> bounded = new ArrayList<>(messages);
        long characters = characters(bounded);
        for (int index = 0; index < bounded.size() && characters > budget; index++) {
            ProviderChatMessage message = bounded.get(index);
            if (message.role() != ProviderChatMessage.Role.TOOL
                    || ELIDED_TOOL_CONTENT.equals(message.content())) {
                continue;
            }
            characters -= message.content().length() - ELIDED_TOOL_CONTENT.length();
            bounded.set(index, ProviderChatMessage.tool(
                    message.toolCallId(), message.toolName(), ELIDED_TOOL_CONTENT));
        }
        if (characters > budget) {
            throw new ProviderGatewayException(
                    ModelGatewayErrorCode.MODEL_CAPABILITY_UNSUPPORTED,
                    "The Coding stage context exceeds the model request budget.");
        }
        return List.copyOf(bounded);
    }

    /** Counts exactly what ProviderChatRequest bounds. */
    private static long characters(List<ProviderChatMessage> messages) {
        return messages.stream()
                .mapToLong(message -> message.content().length()
                        + message.toolCalls().stream()
                                .mapToLong(call -> call.arguments().length())
                                .sum())
                .sum();
    }

    /**
     * A reply that is one whole object followed by a stray brace or a trailing sentence is
     * still recoverable, because the first balanced object is the answer. Cutting to the
     * last brace instead keeps the stray one inside the span and the repair changes nothing.
     * A reply carrying a second object is left alone, because which one was meant is a guess.
     */
    static String firstBalancedJsonObject(String raw) {
        if (raw == null) {
            return null;
        }
        int start = raw.indexOf('{');
        if (start < 0) {
            return raw;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int index = start; index < raw.length(); index++) {
            char current = raw.charAt(index);
            if (inString) {
                if (escaped) {
                    escaped = false;
                }
                else if (current == '\\') {
                    escaped = true;
                }
                else if (current == '"') {
                    inString = false;
                }
                continue;
            }
            if (current == '"') {
                inString = true;
            }
            else if (current == '{') {
                depth++;
            }
            else if (current == '}' && --depth == 0) {
                // Two whole envelopes stay ambiguous and must still be refused, so the
                // first one is only taken when nothing after it starts another object.
                return raw.indexOf('{', index + 1) < 0
                        ? raw.substring(start, index + 1)
                        : raw;
            }
        }
        return StructuredOutputGuard.extractOutermostJsonObject(raw);
    }

    private ParsedAssistant parseProviderToolEnvelope(
            CodingModelTurnContract.Request request,
            String content,
            java.util.function.Consumer<JsonNode> envelopeDiagnostic) {
        StructuredOutputGuard.ValidatedOutput<String> validated;
        try {
            validated = STRUCTURED_OUTPUT_GUARD.validateOrRepair(
                    content,
                    candidate -> readProviderToolEnvelope(request, candidate) != null,
                    CodingModelTurnService::firstBalancedJsonObject);
        }
        catch (ProviderGatewayException failure) {
            throw envelopeRejected(request, content, envelopeDiagnostic);
        }
        ParsedAssistant parsed = readProviderToolEnvelope(request, validated.value());
        if (parsed == null) {
            throw envelopeRejected(request, validated.value(), envelopeDiagnostic);
        }
        return parsed;
    }

    /** Reports the structural reason, then fails exactly as this path always has. */
    private ProviderGatewayException envelopeRejected(
            CodingModelTurnContract.Request request,
            String content,
            java.util.function.Consumer<JsonNode> envelopeDiagnostic) {
        try {
            envelopeDiagnostic.accept(diagnosticFor(request, content));
        }
        catch (RuntimeException ignored) {
            // A diagnostic sink must never change how the turn fails.
        }
        return new ProviderGatewayException(
                ModelGatewayErrorCode.MODEL_RESPONSE_INVALID,
                "Model provider returned an invalid Coding tool envelope.");
    }

    /**
     * Describes the shape of a rejected reply so the mismatch is recoverable after the
     * fact. Only structure is recorded. Field names are echoed because the usual miss
     * is an extra or renamed key, and only when they look like plain identifiers, so a
     * model cannot smuggle content through a key.
     */
    private ObjectNode diagnosticFor(
            CodingModelTurnContract.Request request, String content) {
        ObjectNode diagnostic = objectMapper.createObjectNode();
        diagnostic.put("contentLength", content == null ? 0 : content.length());
        JsonNode envelope = StructuredOutputGuard.readSingleJsonObject(
                toolEnvelopeReader, content);
        if (envelope == null) {
            diagnostic.put("reason", "NOT_ONE_JSON_OBJECT");
            return diagnostic;
        }
        ArrayNode fields = diagnostic.putArray("topLevelFields");
        envelope.fieldNames().forEachRemaining(name -> fields.add(safeKey(name)));
        diagnostic.put("topLevelFieldCount", envelope.size());
        if (envelope.size() != 2
                || !envelope.path("assistant").isTextual()
                || !envelope.path("toolCalls").isArray()) {
            diagnostic.put("reason", "TOP_LEVEL_SHAPE");
            return diagnostic;
        }
        JsonNode toolCalls = envelope.path("toolCalls");
        diagnostic.put("toolCallCount", toolCalls.size());
        if (toolCalls.size() > 1) {
            diagnostic.put("reason", "TOO_MANY_TOOL_CALLS");
            return diagnostic;
        }
        for (JsonNode call : toolCalls) {
            ArrayNode callFields = diagnostic.putArray("toolCallFields");
            if (call.isObject()) {
                call.fieldNames().forEachRemaining(name -> callFields.add(safeKey(name)));
            }
            if (!call.isObject() || call.size() != 2
                    || !call.path("name").isTextual()
                    || !call.path("arguments").isObject()) {
                diagnostic.put("reason", "TOOL_CALL_SHAPE");
                return diagnostic;
            }
            String name = call.path("name").asText();
            diagnostic.put("toolName", safeKey(name));
            JsonNode schema = null;
            for (JsonNode declared : request.toolSchemas()) {
                if (name.equals(declared.path("name").asText())) {
                    schema = declared.path("inputSchema");
                }
            }
            if (schema == null) {
                diagnostic.put("reason", "TOOL_NOT_DECLARED");
                return diagnostic;
            }
            ArrayNode argumentFields = diagnostic.putArray("argumentFields");
            call.path("arguments").fieldNames()
                    .forEachRemaining(field -> argumentFields.add(safeKey(field)));
            if (!matchesInputSchema(call.path("arguments"), schema)) {
                diagnostic.put("reason", "TOOL_ARGUMENTS_OFF_SCHEMA");
                return diagnostic;
            }
        }
        diagnostic.put("reason", envelope.path("assistant").asText().isBlank()
                ? "EMPTY_ASSISTANT_WITHOUT_TOOL_CALL" : "UNKNOWN");
        return diagnostic;
    }

    /** Keeps a reply's own key out of the record unless it is a plain identifier. */
    private static String safeKey(String name) {
        return name != null && SAFE_DIAGNOSTIC_KEY.matcher(name).matches() ? name : "<other>";
    }

    private ParsedAssistant readProviderToolEnvelope(
            CodingModelTurnContract.Request request, String content) {
        if (content == null) {
            return null;
        }
        try {
            JsonNode envelope = StructuredOutputGuard.readSingleJsonObject(
                    toolEnvelopeReader, content);
            // A finished stage answer usually arrives as the result object itself rather
            // than wrapped inside the assistant string. Both spell the same terminal
            // reply, so the direct shape is accepted instead of re-asked.
            if (envelope != null
                    && envelope.size() == 2
                    && envelope.path("port").isTextual()
                    && envelope.path("payload").isObject()) {
                return new ParsedAssistant(envelope.toString(), List.of());
            }
            if (envelope == null
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
            return null;
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

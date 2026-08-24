package org.urizo.axmodulestudio.backend.coding.dto;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

public final class CodingModelTurnContract {

    public static final String SCHEMA_VERSION = "1.0";
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{7,127}$");
    private static final Pattern NODE_NAME = Pattern.compile("^[a-z][a-z0-9_-]{0,119}$");
    private static final Pattern PROMPT_VERSION = Pattern.compile("^[A-Za-z0-9._-]{1,120}$");
    private static final Pattern SHA256_DIGEST = Pattern.compile("^sha256:[0-9a-f]{64}$");
    private static final Pattern TOOL_NAME = Pattern.compile("^[a-z][a-z0-9_]{0,119}$");
    private static final Set<String> CAPABILITIES = Set.of("CHAT", "STRUCTURED_OUTPUT", "TOOL_CALLING");

    private CodingModelTurnContract() {
    }

    public record Request(
            String schemaVersion,
            UUID turnId,
            UUID jobId,
            UUID traceId,
            String idempotencyKey,
            int attempt,
            int expectedStateVersion,
            String nodeName,
            String promptVersion,
            String contextDigest,
            List<String> requiredCapabilities,
            List<JsonNode> messages,
            List<JsonNode> toolSchemas,
            JsonNode responseFormat,
            Instant deadlineAt) {

        public Request {
            if (!SCHEMA_VERSION.equals(schemaVersion)) {
                throw new IllegalArgumentException("Unsupported model turn schemaVersion.");
            }
            Objects.requireNonNull(turnId, "turnId is required");
            Objects.requireNonNull(jobId, "jobId is required");
            Objects.requireNonNull(traceId, "traceId is required");
            requireMatch(idempotencyKey, IDEMPOTENCY_KEY, "idempotencyKey");
            if (attempt < 1 || expectedStateVersion < 1) {
                throw new IllegalArgumentException("attempt and expectedStateVersion must be positive.");
            }
            requireMatch(nodeName, NODE_NAME, "nodeName");
            requireMatch(promptVersion, PROMPT_VERSION, "promptVersion");
            requireMatch(contextDigest, SHA256_DIGEST, "contextDigest");
            requiredCapabilities = List.copyOf(Objects.requireNonNull(
                    requiredCapabilities, "requiredCapabilities are required"));
            messages = copyNodes(Objects.requireNonNull(messages, "messages are required"));
            toolSchemas = copyNodes(Objects.requireNonNull(toolSchemas, "toolSchemas are required"));
            responseFormat = Objects.requireNonNull(responseFormat, "responseFormat is required").deepCopy();
            deadlineAt = Objects.requireNonNull(deadlineAt, "deadlineAt is required");
            if (requiredCapabilities.isEmpty()
                    || requiredCapabilities.size() != Set.copyOf(requiredCapabilities).size()
                    || !CAPABILITIES.containsAll(requiredCapabilities)) {
                throw new IllegalArgumentException("requiredCapabilities are invalid.");
            }
            if (messages.isEmpty() || messages.size() > 200 || toolSchemas.size() > 50) {
                throw new IllegalArgumentException("Model turn collection sizes are invalid.");
            }
            messages.forEach(CodingModelTurnContract::validateMessage);
            toolSchemas.forEach(CodingModelTurnContract::validateToolSchema);
            Set<String> capabilities = Set.copyOf(requiredCapabilities);
            if (capabilities.contains("STRUCTURED_OUTPUT") && capabilities.contains("TOOL_CALLING")) {
                throw new IllegalArgumentException("Structured output and tool calling cannot be combined.");
            }
            if (capabilities.contains("TOOL_CALLING") != !toolSchemas.isEmpty()) {
                throw new IllegalArgumentException("toolSchemas do not match TOOL_CALLING capability.");
            }
            boolean structuredFormat = validateResponseFormat(responseFormat);
            if (structuredFormat != capabilities.contains("STRUCTURED_OUTPUT")) {
                throw new IllegalArgumentException("responseFormat does not match STRUCTURED_OUTPUT capability.");
            }
        }

        @Override
        public List<JsonNode> messages() {
            return copyNodes(messages);
        }

        @Override
        public List<JsonNode> toolSchemas() {
            return copyNodes(toolSchemas);
        }

        @Override
        public JsonNode responseFormat() {
            return responseFormat.deepCopy();
        }

        @Override
        public String toString() {
            return "CodingModelTurnRequest[turnId=" + turnId
                    + ", jobId=" + jobId
                    + ", messages=REDACTED, toolSchemas=REDACTED]";
        }
    }

    public record Response(
            String schemaVersion,
            UUID turnId,
            UUID jobId,
            UUID traceId,
            String idempotencyKey,
            Assistant assistant,
            List<ToolCall> toolCalls,
            TextResponseFormat responseFormat,
            SelectedModel selectedModel,
            TokenUsage usage,
            long latencyMs,
            String finishReason,
            Instant completedAt) {

        @Override
        public String toString() {
            return "CodingModelTurnResponse[turnId=" + turnId
                    + ", jobId=" + jobId + ", assistant=REDACTED]";
        }
    }

    public record Assistant(String role, String content) {
        @Override
        public String toString() {
            return "Assistant[role=" + role + ", content=REDACTED]";
        }
    }

    public record ToolCall(UUID toolCallId, String name, JsonNode arguments) {
    }

    public record TextResponseFormat(String type) {
        public static TextResponseFormat text() {
            return new TextResponseFormat("TEXT");
        }
    }

    public record SelectedModel(String provider, String modelId) {
    }

    public record TokenUsage(int inputTokens, int outputTokens, int totalTokens) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorDetail(
            String code,
            String message,
            boolean retryable,
            Long retryAfterMs) {
    }

    public record PreContextErrorEnvelope(
            String schemaVersion,
            UUID requestId,
            UUID traceId,
            ErrorDetail error) {
    }

    public record JobScopedErrorEnvelope(
            String schemaVersion,
            UUID traceId,
            UUID jobId,
            String idempotencyKey,
            ErrorDetail error) {
    }

    private static void requireMatch(String value, Pattern pattern, String field) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " is invalid.");
        }
    }

    private static void validateMessage(JsonNode message) {
        if (!message.isObject() || !message.path("role").isTextual()) {
            throw new IllegalArgumentException("Model turn message is invalid.");
        }
        String role = message.path("role").textValue();
        if (Set.of("system", "user", "assistant").contains(role)
                && exactFields(message, "role", "content")) {
            requireText(message.path("content"), 1, 200_000, "message content");
            return;
        }
        if ("assistant".equals(role) && exactFields(message, "role", "content", "toolCalls")) {
            requireText(message.path("content"), 0, 200_000, "assistant content");
            JsonNode toolCalls = message.path("toolCalls");
            if (!toolCalls.isArray() || toolCalls.isEmpty() || toolCalls.size() > 50) {
                throw new IllegalArgumentException("Assistant toolCalls are invalid.");
            }
            toolCalls.forEach(CodingModelTurnContract::validateToolCall);
            return;
        }
        if ("tool".equals(role)
                && exactFields(message, "role", "toolCallId", "executionId", "result", "content")) {
            requireUuid(message.path("toolCallId"), "toolCallId");
            requireUuid(message.path("executionId"), "executionId");
            JsonNode result = message.path("result");
            if (!result.isObject()
                    || !exactFields(result, "mediaType", "resultRef", "sizeBytes", "digest")
                    || !result.path("mediaType").isTextual()
                    || !result.path("resultRef").isTextual()
                    || !result.path("sizeBytes").isIntegralNumber()
                    || !result.path("sizeBytes").canConvertToLong()
                    || result.path("sizeBytes").longValue() < 0) {
                throw new IllegalArgumentException("Tool result reference is invalid.");
            }
            requireMatch(result.path("digest").textValue(), SHA256_DIGEST, "result digest");
            requireText(message.path("content"), 0, 200_000, "tool result content");
            return;
        }
        throw new IllegalArgumentException("Model turn message shape is invalid.");
    }

    private static void validateToolCall(JsonNode toolCall) {
        if (!toolCall.isObject() || !exactFields(toolCall, "toolCallId", "name", "arguments")) {
            throw new IllegalArgumentException("Tool call candidate is invalid.");
        }
        requireUuid(toolCall.path("toolCallId"), "toolCallId");
        requireMatch(toolCall.path("name").textValue(), TOOL_NAME, "tool name");
        if (!toolCall.path("arguments").isObject()) {
            throw new IllegalArgumentException("Tool call arguments are invalid.");
        }
    }

    private static void validateToolSchema(JsonNode toolSchema) {
        if (!toolSchema.isObject()
                || !exactFields(toolSchema, "name", "description", "inputSchema", "schemaDigest")) {
            throw new IllegalArgumentException("Tool schema is invalid.");
        }
        requireMatch(toolSchema.path("name").textValue(), TOOL_NAME, "tool schema name");
        requireText(toolSchema.path("description"), 1, 2_000, "tool schema description");
        if (!toolSchema.path("inputSchema").isObject()) {
            throw new IllegalArgumentException("Tool input schema is invalid.");
        }
        requireMatch(toolSchema.path("schemaDigest").textValue(), SHA256_DIGEST, "tool schema digest");
    }

    private static boolean validateResponseFormat(JsonNode responseFormat) {
        if (!responseFormat.isObject() || !responseFormat.path("type").isTextual()) {
            throw new IllegalArgumentException("responseFormat is invalid.");
        }
        if ("TEXT".equals(responseFormat.path("type").textValue())) {
            if (!exactFields(responseFormat, "type")) {
                throw new IllegalArgumentException("TEXT responseFormat contains unknown fields.");
            }
            return false;
        }
        if ("JSON_SCHEMA".equals(responseFormat.path("type").textValue())) {
            if (!exactFields(responseFormat, "type", "schemaDigest", "outputSchema")
                    || !responseFormat.path("outputSchema").isObject()) {
                throw new IllegalArgumentException("JSON_SCHEMA responseFormat is invalid.");
            }
            requireMatch(responseFormat.path("schemaDigest").textValue(), SHA256_DIGEST,
                    "response schema digest");
            return true;
        }
        throw new IllegalArgumentException("responseFormat type is invalid.");
    }

    private static boolean exactFields(JsonNode object, String... names) {
        Set<String> expected = Set.of(names);
        Set<String> actual = new java.util.HashSet<>();
        object.fieldNames().forEachRemaining(actual::add);
        return actual.equals(expected);
    }

    private static void requireText(JsonNode value, int minimum, int maximum, String field) {
        if (!value.isTextual() || value.textValue().length() < minimum
                || value.textValue().length() > maximum) {
            throw new IllegalArgumentException(field + " is invalid.");
        }
    }

    private static void requireUuid(JsonNode value, String field) {
        try {
            if (!value.isTextual()) {
                throw new IllegalArgumentException();
            }
            UUID.fromString(value.textValue());
        }
        catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException(field + " is invalid.");
        }
    }

    private static List<JsonNode> copyNodes(List<JsonNode> nodes) {
        return nodes.stream()
                .map(node -> (JsonNode) Objects.requireNonNull(
                        node, "JSON node cannot be null").deepCopy())
                .toList();
    }
}

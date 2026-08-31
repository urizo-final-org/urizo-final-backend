package org.urizo.axmodulestudio.backend.integration.ai.gateway;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.urizo.axmodulestudio.backend.integration.ai.mcp.McpPlatformContract;

public record ProviderToolDefinition(
        String name,
        String description,
        JsonNode inputSchema,
        String schemaDigest) {

    public static final String LEGACY_TOOL_PROMPT_PREFIX =
            "Return exactly one JSON object with fields assistant and toolCalls. "
                    + "assistant must be a string and toolCalls must contain zero or one "
                    + "declared tool call with name and arguments. Do not add markdown.\n"
                    + "Declared tools: ";
    private static final int MAX_DESCRIPTION_CHARACTERS = 2_000;
    private static final Pattern TOOL_NAME = Pattern.compile("^[a-z][a-z0-9_]{0,119}$");
    private static final Pattern SHA256_DIGEST = Pattern.compile("^sha256:[0-9a-f]{64}$");
    private static final Set<String> CONTRACT_FIELDS =
            Set.of("name", "description", "inputSchema", "schemaDigest");
    private static final ObjectMapper STRICT_JSON = strictMapper();

    public ProviderToolDefinition {
        name = Objects.requireNonNull(name, "name is required");
        description = Objects.requireNonNull(description, "description is required");
        schemaDigest = Objects.requireNonNull(schemaDigest, "schemaDigest is required");
        if (!TOOL_NAME.matcher(name).matches()
                || !McpPlatformContract.allowedToolNames().contains(name)) {
            throw new IllegalArgumentException("Tool name is not in the approved allowlist.");
        }
        if (description.isBlank() || description.length() > MAX_DESCRIPTION_CHARACTERS) {
            throw new IllegalArgumentException("Tool description is invalid.");
        }
        inputSchema = ProviderJsonSchema.validateAndCanonicalize(
                Objects.requireNonNull(inputSchema, "inputSchema is required"));
        if (!SHA256_DIGEST.matcher(schemaDigest).matches()
                || !ProviderJsonSchema.matchesDigest(inputSchema, schemaDigest)) {
            throw new IllegalArgumentException("Tool schema digest does not match its canonical schema.");
        }
    }

    public ProviderToolDefinition(
            String name, String description, JsonNode inputSchema) {
        this(name, description, inputSchema, ProviderJsonSchema.digest(
                ProviderJsonSchema.validateAndCanonicalize(inputSchema)));
    }

    public static ProviderToolDefinition fromContract(JsonNode contract) {
        if (contract == null || !contract.isObject()) {
            throw new IllegalArgumentException("Tool definition contract is invalid.");
        }
        Set<String> fields = new HashSet<>();
        contract.fieldNames().forEachRemaining(fields::add);
        if (!fields.equals(CONTRACT_FIELDS)
                || !contract.path("name").isTextual()
                || !contract.path("description").isTextual()
                || !contract.path("inputSchema").isObject()
                || !contract.path("schemaDigest").isTextual()) {
            throw new IllegalArgumentException("Tool definition contract is invalid.");
        }
        return new ProviderToolDefinition(
                contract.path("name").textValue(),
                contract.path("description").textValue(),
                contract.path("inputSchema"),
                contract.path("schemaDigest").textValue());
    }

    static List<ProviderToolDefinition> legacyDefinitions(List<ProviderChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        ProviderChatMessage first = messages.get(0);
        if (first.role() != ProviderChatMessage.Role.SYSTEM
                || !first.content().startsWith(LEGACY_TOOL_PROMPT_PREFIX)) {
            return List.of();
        }
        String rawDefinitions = first.content().substring(LEGACY_TOOL_PROMPT_PREFIX.length());
        try {
            JsonNode definitions = STRICT_JSON.readTree(rawDefinitions);
            if (definitions == null || !definitions.isArray()
                    || definitions.isEmpty() || definitions.size() > 50) {
                throw new IllegalArgumentException("Declared tool definitions are invalid.");
            }
            return java.util.stream.StreamSupport.stream(definitions.spliterator(), false)
                    .map(ProviderToolDefinition::fromContract)
                    .toList();
        }
        catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("Declared tool definitions are invalid.");
        }
    }

    public String providerInputSchema() {
        return ProviderJsonSchema.providerSchema(inputSchema);
    }

    public String normalizeArguments(String rawArguments) {
        return ProviderJsonSchema.normalizeArguments(inputSchema, rawArguments);
    }

    @Override
    public JsonNode inputSchema() {
        return inputSchema.deepCopy();
    }

    @Override
    public String toString() {
        return "ProviderToolDefinition[name=" + name
                + ", description=REDACTED, inputSchema=REDACTED, schemaDigest="
                + schemaDigest + "]";
    }

    @SuppressWarnings("deprecation")
    private static ObjectMapper strictMapper() {
        JsonFactory factory = new JsonFactory();
        factory.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        ObjectMapper mapper = new ObjectMapper(factory);
        mapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        return mapper;
    }
}

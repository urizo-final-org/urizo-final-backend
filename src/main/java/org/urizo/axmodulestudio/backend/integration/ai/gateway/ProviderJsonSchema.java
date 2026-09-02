package org.urizo.axmodulestudio.backend.integration.ai.gateway;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

final class ProviderJsonSchema {

    private static final int MAX_SCHEMA_CHARACTERS = 32_768;
    private static final int MAX_ARGUMENT_CHARACTERS = 32_768;
    private static final int MAX_SCHEMA_DEPTH = 8;
    private static final int MAX_ARGUMENT_DEPTH = 16;
    private static final int MAX_SCHEMA_NODES = 512;
    private static final int MAX_ARGUMENT_NODES = 2_048;
    private static final int MAX_PROPERTIES = 200;
    private static final int MAX_ARRAY_ITEMS = 1_000;
    private static final Pattern PROPERTY_NAME =
            Pattern.compile("^[A-Za-z][A-Za-z0-9_]{0,119}$");
    private static final Set<String> OBJECT_FIELDS =
            Set.of("type", "properties", "required", "additionalProperties");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper STRICT_JSON = strictMapper();

    private ProviderJsonSchema() {
    }

    static JsonNode validateAndCanonicalize(JsonNode inputSchema) {
        if (inputSchema == null || !inputSchema.isObject()) {
            throw invalidSchema();
        }
        Bounds bounds = new Bounds(MAX_SCHEMA_NODES);
        validateTreeBounds(inputSchema, 1, MAX_SCHEMA_DEPTH, bounds, true);
        SchemaStats stats = new SchemaStats();
        validateSchemaNode(inputSchema, 1, true, stats);
        JsonNode canonical = canonicalize(inputSchema);
        if (write(canonical).length() > MAX_SCHEMA_CHARACTERS) {
            throw invalidSchema();
        }
        return canonical;
    }

    static JsonNode validateStrictOutputAndCanonicalize(JsonNode inputSchema) {
        JsonNode canonical = validateAndCanonicalize(inputSchema);
        validateStrictOutputNode(canonical);
        return canonical;
    }

    static String providerSchema(JsonNode canonicalSchema) {
        return write(canonicalSchema);
    }

    static String canonicalJson(JsonNode value) {
        return write(canonicalize(value));
    }

    static String digest(JsonNode canonicalSchema) {
        byte[] bytes = canonicalJson(canonicalSchema).getBytes(StandardCharsets.UTF_8);
        try {
            return "sha256:" + java.util.HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        }
        catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable.", failure);
        }
    }

    static boolean matchesDigest(JsonNode canonicalSchema, String expected) {
        if (digest(canonicalSchema).equals(expected)) {
            return true;
        }
        JsonNode withoutEmptyRequired = withoutEmptyRequired(canonicalSchema);
        return !withoutEmptyRequired.equals(canonicalSchema)
                && digest(withoutEmptyRequired).equals(expected);
    }

    static String normalizeArguments(JsonNode canonicalSchema, String rawArguments) {
        return canonicalJson(normalizeObject(canonicalSchema, rawArguments));
    }

    static JsonNode normalizeObject(JsonNode canonicalSchema, String rawArguments) {
        if (rawArguments == null || rawArguments.isBlank()
                || rawArguments.length() > MAX_ARGUMENT_CHARACTERS) {
            throw invalidArguments();
        }
        try {
            JsonNode arguments = STRICT_JSON.readTree(rawArguments);
            if (arguments == null || !arguments.isObject()) {
                throw invalidArguments();
            }
            Bounds bounds = new Bounds(MAX_ARGUMENT_NODES);
            validateTreeBounds(arguments, 1, MAX_ARGUMENT_DEPTH, bounds, false);
            validateValue(arguments, canonicalSchema);
            return canonicalize(arguments);
        }
        catch (JsonProcessingException failure) {
            throw invalidArguments();
        }
    }

    private static void validateSchemaNode(
            JsonNode schema, int depth, boolean root, SchemaStats stats) {
        if (!schema.isObject() || depth > MAX_SCHEMA_DEPTH
                || !schema.path("type").isTextual()) {
            throw invalidSchema();
        }
        String type = schema.path("type").textValue();
        if ("object".equals(type)) {
            validateObjectSchema(schema, depth, root, stats);
            return;
        }
        if (!("string".equals(type) || "integer".equals(type)) || schema.size() != 1) {
            throw invalidSchema();
        }
    }

    private static void validateObjectSchema(
            JsonNode schema, int depth, boolean root, SchemaStats stats) {
        schema.fieldNames().forEachRemaining(field -> {
            if (!OBJECT_FIELDS.contains(field)) {
                throw invalidSchema();
            }
        });
        JsonNode properties = schema.path("properties");
        boolean declaredProperties = schema.has("properties");
        if (root && (!declaredProperties || !properties.isObject()
                || !schema.has("required") || !schema.path("required").isArray()
                || !schema.has("additionalProperties")
                || !schema.path("additionalProperties").isBoolean()
                || schema.path("additionalProperties").booleanValue())) {
            throw invalidSchema();
        }
        if (declaredProperties && !properties.isObject()
                || schema.has("required") && !schema.path("required").isArray()
                || schema.has("additionalProperties")
                        && !schema.path("additionalProperties").isBoolean()) {
            throw invalidSchema();
        }
        if (!declaredProperties && (schema.has("required")
                || schema.has("additionalProperties"))) {
            throw invalidSchema();
        }
        if (!declaredProperties) {
            return;
        }

        Set<String> propertyNames = new HashSet<>();
        properties.fields().forEachRemaining(entry -> {
            if (!PROPERTY_NAME.matcher(entry.getKey()).matches()
                    || !propertyNames.add(entry.getKey())) {
                throw invalidSchema();
            }
            stats.properties++;
            if (stats.properties > MAX_PROPERTIES) {
                throw invalidSchema();
            }
            validateSchemaNode(entry.getValue(), depth + 1, false, stats);
        });
        if (schema.has("required")) {
            JsonNode required = schema.path("required");
            if (required.size() > propertyNames.size()) {
                throw invalidSchema();
            }
            Set<String> requiredNames = new HashSet<>();
            required.forEach(name -> {
                if (!name.isTextual() || !propertyNames.contains(name.textValue())
                        || !requiredNames.add(name.textValue())) {
                    throw invalidSchema();
                }
            });
        }
    }

    private static void validateStrictOutputNode(JsonNode schema) {
        if (!"object".equals(schema.path("type").asText())) {
            return;
        }
        JsonNode properties = schema.path("properties");
        JsonNode required = schema.path("required");
        if (!properties.isObject() || !required.isArray()
                || !schema.path("additionalProperties").isBoolean()
                || schema.path("additionalProperties").booleanValue()) {
            throw invalidStrictOutputSchema();
        }

        Set<String> propertyNames = new HashSet<>();
        properties.fieldNames().forEachRemaining(propertyNames::add);
        Set<String> requiredNames = new HashSet<>();
        required.forEach(name -> requiredNames.add(name.textValue()));
        if (!requiredNames.equals(propertyNames)) {
            throw invalidStrictOutputSchema();
        }
        properties.forEach(ProviderJsonSchema::validateStrictOutputNode);
    }

    private static void validateValue(JsonNode value, JsonNode schema) {
        switch (schema.path("type").asText()) {
            case "object" -> validateObjectValue(value, schema);
            case "string" -> {
                if (!value.isTextual()) {
                    throw invalidArguments();
                }
            }
            case "integer" -> {
                if (!value.isIntegralNumber()) {
                    throw invalidArguments();
                }
            }
            default -> throw invalidArguments();
        }
    }

    private static void validateObjectValue(JsonNode value, JsonNode schema) {
        if (!value.isObject()) {
            throw invalidArguments();
        }
        JsonNode properties = schema.path("properties");
        if (!properties.isObject()) {
            return;
        }
        JsonNode required = schema.path("required");
        if (required.isArray()) {
            required.forEach(name -> {
                if (!value.has(name.textValue())) {
                    throw invalidArguments();
                }
            });
        }
        boolean allowAdditional = !schema.has("additionalProperties")
                || schema.path("additionalProperties").booleanValue();
        value.fields().forEachRemaining(entry -> {
            JsonNode propertySchema = properties.path(entry.getKey());
            if (propertySchema.isMissingNode()) {
                if (!allowAdditional) {
                    throw invalidArguments();
                }
            }
            else {
                validateValue(entry.getValue(), propertySchema);
            }
        });
    }

    private static JsonNode withoutEmptyRequired(JsonNode value) {
        if (value.isObject()) {
            ObjectNode result = JsonNodeFactory.instance.objectNode();
            TreeMap<String, JsonNode> fields = new TreeMap<>();
            value.fields().forEachRemaining(entry -> fields.put(entry.getKey(), entry.getValue()));
            fields.forEach((name, item) -> {
                if (!("required".equals(name) && item.isArray() && item.isEmpty())) {
                    result.set(name, withoutEmptyRequired(item));
                }
            });
            return result;
        }
        if (value.isArray()) {
            ArrayNode result = JsonNodeFactory.instance.arrayNode();
            value.forEach(item -> result.add(withoutEmptyRequired(item)));
            return result;
        }
        return value.deepCopy();
    }

    private static JsonNode canonicalize(JsonNode value) {
        if (value.isObject()) {
            ObjectNode result = JsonNodeFactory.instance.objectNode();
            TreeMap<String, JsonNode> fields = new TreeMap<>();
            value.fields().forEachRemaining(entry -> fields.put(entry.getKey(), entry.getValue()));
            fields.forEach((name, item) -> result.set(name, canonicalize(item)));
            return result;
        }
        if (value.isArray()) {
            ArrayNode result = JsonNodeFactory.instance.arrayNode();
            value.forEach(item -> result.add(canonicalize(item)));
            return result;
        }
        return value.deepCopy();
    }

    private static void validateTreeBounds(
            JsonNode value, int depth, int maxDepth, Bounds bounds, boolean schema) {
        if (depth > maxDepth || ++bounds.nodes > bounds.maxNodes
                || value.isArray() && value.size() > MAX_ARRAY_ITEMS) {
            throw schema ? invalidSchema() : invalidArguments();
        }
        if (value.isObject()) {
            value.fields().forEachRemaining(entry ->
                    validateTreeBounds(entry.getValue(), depth + 1, maxDepth, bounds, schema));
        }
        else if (value.isArray()) {
            value.forEach(item -> validateTreeBounds(
                    item, depth + 1, maxDepth, bounds, schema));
        }
    }

    private static String write(JsonNode value) {
        try {
            return JSON.writeValueAsString(value);
        }
        catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("JSON value could not be normalized.");
        }
    }

    @SuppressWarnings("deprecation")
    private static ObjectMapper strictMapper() {
        JsonFactory factory = new JsonFactory();
        factory.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        ObjectMapper mapper = new ObjectMapper(factory);
        mapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        return mapper;
    }

    private static IllegalArgumentException invalidSchema() {
        return new IllegalArgumentException(
                "Tool input JSON Schema is invalid or exceeds its bounds.");
    }

    private static IllegalArgumentException invalidStrictOutputSchema() {
        return new IllegalArgumentException(
                "Structured output JSON Schema must close every object and require every field.");
    }

    private static IllegalArgumentException invalidArguments() {
        return new IllegalArgumentException("Native tool call arguments are invalid.");
    }

    private static final class SchemaStats {
        private int properties;
    }

    private static final class Bounds {
        private final int maxNodes;
        private int nodes;

        private Bounds(int maxNodes) {
            this.maxNodes = maxNodes;
        }
    }
}

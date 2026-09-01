package org.urizo.axmodulestudio.backend.integration.ai.gateway;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

public record ProviderResponseFormat(
        Type type,
        String schemaDigest,
        JsonNode outputSchema) {

    private static final Set<String> TEXT_FIELDS = Set.of("type");
    private static final Set<String> JSON_SCHEMA_FIELDS =
            Set.of("type", "schemaDigest", "outputSchema");
    private static final StructuredOutputGuard OUTPUT_GUARD =
            new StructuredOutputGuard();

    public ProviderResponseFormat {
        type = Objects.requireNonNull(type, "type is required");
        if (type == Type.TEXT) {
            if (schemaDigest != null || outputSchema != null) {
                throw new IllegalArgumentException("TEXT response format cannot carry a schema.");
            }
        }
        else {
            JsonNode canonical = ProviderJsonSchema.validateAndCanonicalize(
                    Objects.requireNonNull(outputSchema, "outputSchema is required"));
            if (schemaDigest == null
                    || !ProviderJsonSchema.matchesDigest(canonical, schemaDigest)) {
                throw new IllegalArgumentException(
                        "Structured output schema digest does not match its canonical schema.");
            }
            outputSchema = canonical;
        }
    }

    public static ProviderResponseFormat text() {
        return new ProviderResponseFormat(Type.TEXT, null, null);
    }

    public static ProviderResponseFormat jsonSchema(JsonNode outputSchema) {
        JsonNode canonical = ProviderJsonSchema.validateAndCanonicalize(outputSchema);
        return new ProviderResponseFormat(
                Type.JSON_SCHEMA, ProviderJsonSchema.digest(canonical), canonical);
    }

    public static ProviderResponseFormat fromContract(JsonNode contract) {
        if (contract == null || !contract.isObject()
                || !contract.path("type").isTextual()) {
            throw new IllegalArgumentException("Provider response format is invalid.");
        }
        Set<String> fields = new HashSet<>();
        contract.fieldNames().forEachRemaining(fields::add);
        if ("TEXT".equals(contract.path("type").textValue())
                && fields.equals(TEXT_FIELDS)) {
            return text();
        }
        if ("JSON_SCHEMA".equals(contract.path("type").textValue())
                && fields.equals(JSON_SCHEMA_FIELDS)
                && contract.path("schemaDigest").isTextual()
                && contract.path("outputSchema").isObject()) {
            return new ProviderResponseFormat(
                    Type.JSON_SCHEMA,
                    contract.path("schemaDigest").textValue(),
                    contract.path("outputSchema"));
        }
        throw new IllegalArgumentException("Provider response format is invalid.");
    }

    public boolean structured() {
        return type == Type.JSON_SCHEMA;
    }

    public ObjectNode requestContract() {
        ObjectNode contract = JsonNodeFactory.instance.objectNode()
                .put("type", type.name());
        if (structured()) {
            contract.put("schemaDigest", schemaDigest);
            contract.set("outputSchema", outputSchema.deepCopy());
        }
        return contract;
    }

    public String providerOutputSchema() {
        if (!structured()) {
            throw new IllegalStateException("TEXT response format has no output schema.");
        }
        return ProviderJsonSchema.providerSchema(outputSchema);
    }

    public JsonNode validateOrRepair(String rawOutput) {
        if (!structured()) {
            throw new IllegalStateException("TEXT response format has no structured output.");
        }
        StructuredOutputGuard.ValidatedOutput<String> validated =
                OUTPUT_GUARD.validateOrRepair(
                        rawOutput,
                        this::matches,
                        StructuredOutputGuard::extractOutermostJsonObject);
        try {
            return ProviderJsonSchema.normalizeObject(outputSchema, validated.value());
        }
        catch (IllegalArgumentException failure) {
            throw new ProviderGatewayException(
                    ModelGatewayErrorCode.MODEL_RESPONSE_INVALID,
                    "Structured model output failed schema validation after one repair.");
        }
    }

    @Override
    public JsonNode outputSchema() {
        return outputSchema == null ? null : outputSchema.deepCopy();
    }

    @Override
    public String toString() {
        return "ProviderResponseFormat[type=" + type
                + ", schemaDigest=" + schemaDigest
                + ", outputSchema=REDACTED]";
    }

    private boolean matches(String candidate) {
        try {
            ProviderJsonSchema.normalizeObject(outputSchema, candidate);
            return true;
        }
        catch (IllegalArgumentException failure) {
            return false;
        }
    }

    public enum Type {
        TEXT,
        JSON_SCHEMA
    }
}

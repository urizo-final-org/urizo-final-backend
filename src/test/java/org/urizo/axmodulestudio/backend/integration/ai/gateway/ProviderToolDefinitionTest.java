package org.urizo.axmodulestudio.backend.integration.ai.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class ProviderToolDefinitionTest {

    private static final String READ_FILE_DIGEST =
            "sha256:39b714704935190561ed407980480b9a4a0b346b97346e0bff71fb9ace820194";

    @Test
    void validatesCanonicalSchemaDigestAndBuildsProviderProjection() {
        ObjectNode schema = readFileSchema();
        String digest = ProviderJsonSchema.digest(
                ProviderJsonSchema.validateAndCanonicalize(schema));

        ProviderToolDefinition definition = new ProviderToolDefinition(
                "read_file", "Read one approved file.", schema, digest);

        java.util.List<String> fieldNames = new java.util.ArrayList<>();
        definition.inputSchema().fieldNames().forEachRemaining(fieldNames::add);
        assertThat(fieldNames)
                .containsExactly("additionalProperties", "properties", "required", "type");
        assertThat(definition.providerInputSchema())
                .isEqualTo("{\"additionalProperties\":false,\"properties\":"
                        + "{\"path\":{\"type\":\"string\"}},"
                        + "\"required\":[\"path\"],\"type\":\"object\"}");
        assertThat(definition.schemaDigest()).startsWith("sha256:").hasSize(71);
        assertThat(definition.toString())
                .contains("inputSchema=REDACTED")
                .doesNotContain("Read one approved file");
    }

    @Test
    void keepsTheExistingCanonicalReadFileDigestStable() {
        ProviderToolDefinition definition = new ProviderToolDefinition(
                "read_file", "Read one approved file.", readFileSchema(), READ_FILE_DIGEST);

        assertThat(definition.schemaDigest()).isEqualTo(READ_FILE_DIGEST);
        assertThat(ProviderJsonSchema.canonicalJson(definition.inputSchema()))
                .isEqualTo("{\"additionalProperties\":false,\"properties\":"
                        + "{\"path\":{\"type\":\"string\"}},"
                        + "\"required\":[\"path\"],\"type\":\"object\"}");
    }

    @Test
    void acceptsTheExistingSemanticDigestForAnEmptyRequiredSet() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object").put("additionalProperties", false);
        schema.putObject("properties");
        schema.putArray("required");

        ProviderToolDefinition definition = new ProviderToolDefinition(
                "read_diff",
                "Read the approved diff.",
                schema,
                "sha256:99334726611ccf58a148b0814696bfa6fe08c1b2d027e946beccf5a74331c9aa");

        assertThat(definition.normalizeArguments("{}"))
                .isEqualTo("{}");
    }

    @Test
    void keepsOptionalToolInputFieldsIndependentFromStrictResponseFormats() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode()
                .put("type", "object")
                .put("additionalProperties", false);
        schema.putArray("required").add("query");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("query").put("type", "string");
        properties.putObject("scope").put("type", "string");

        ProviderToolDefinition definition = new ProviderToolDefinition(
                "search_code", "Search approved source roots.", schema);

        assertThat(definition.normalizeArguments("{\"query\":\"payload\"}"))
                .isEqualTo("{\"query\":\"payload\"}");
    }

    @Test
    void normalizesArgumentsDeterministicallyAndRejectsMalformedOrUnknownFields() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object").put("additionalProperties", false);
        schema.putArray("required").add("path").add("line");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("path").put("type", "string");
        properties.putObject("line").put("type", "integer");
        ProviderToolDefinition definition = new ProviderToolDefinition(
                "read_file", "Read one approved file.", schema);

        assertThat(definition.normalizeArguments("{\"path\":\"README.md\",\"line\":2}"))
                .isEqualTo("{\"line\":2,\"path\":\"README.md\"}");
        assertThatThrownBy(() -> definition.normalizeArguments(
                "{\"path\":\"README.md\",\"line\":\"2\"}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Native tool call arguments are invalid.");
        assertThatThrownBy(() -> definition.normalizeArguments(
                "{\"path\":\"README.md\",\"line\":2,\"secret\":\"x\"}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Native tool call arguments are invalid.");
        assertThatThrownBy(() -> definition.normalizeArguments(
                "{\"path\":\"README.md\",\"path\":\"other\",\"line\":2}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Native tool call arguments are invalid.");

        for (String invalid : java.util.List.of(
                "{\"path\":\"README.md\",\"line\":2} trailing",
                "[\"README.md\",2]",
                "{\"path\":\"README.md\"}",
                "{\"path\":\"README.md\",\"line\":2,\"extra\":true}")) {
            assertThatThrownBy(() -> definition.normalizeArguments(invalid))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Native tool call arguments are invalid.");
        }
    }

    @Test
    void rejectsUnknownToolsSchemaKeywordsBoundsAndDigestMismatch() {
        ObjectNode unknownToolSchema = readFileSchema();
        assertThatThrownBy(() -> new ProviderToolDefinition(
                "shell_exec", "Run arbitrary shell.", unknownToolSchema))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Tool name is not in the approved allowlist.");

        ObjectNode referenceSchema = readFileSchema();
        ((ObjectNode) referenceSchema.path("properties").path("path"))
                .put("$ref", "#/$defs/path");
        assertThatThrownBy(() -> new ProviderToolDefinition(
                "read_file", "Read one approved file.", referenceSchema))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Tool input JSON Schema is invalid or exceeds its bounds.");

        ObjectNode openRoot = readFileSchema().put("additionalProperties", true);
        assertThatThrownBy(() -> new ProviderToolDefinition(
                "read_file", "Read one approved file.", openRoot))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Tool input JSON Schema is invalid or exceeds its bounds.");

        assertThatThrownBy(() -> new ProviderToolDefinition(
                "read_file", "Read one approved file.", readFileSchema(),
                "sha256:" + "0".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Tool schema digest does not match its canonical schema.");
    }

    @Test
    void allowsBoundedFreeFormNestedObjectsButRejectsExcessiveSchemaDepth() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object").put("additionalProperties", false);
        schema.putArray("required").add("command");
        schema.putObject("properties").putObject("command").put("type", "object");
        ProviderToolDefinition definition = new ProviderToolDefinition(
                "validate_cms_command", "Validate one CMS command.", schema);

        assertThat(definition.normalizeArguments(
                "{\"command\":{\"operation\":\"UPDATE\",\"title\":\"Safe\"}}"))
                .isEqualTo("{\"command\":{\"operation\":\"UPDATE\",\"title\":\"Safe\"}}");

        ObjectNode deep = JsonNodeFactory.instance.objectNode();
        deep.put("type", "object").put("additionalProperties", false);
        deep.putArray("required").add("value");
        ObjectNode properties = deep.putObject("properties");
        ObjectNode current = properties.putObject("value");
        for (int depth = 0; depth < 9; depth++) {
            current.put("type", "object");
            current = current.putObject("properties").putObject("value");
        }
        current.put("type", "string");

        assertThatThrownBy(() -> new ProviderToolDefinition(
                "read_file", "Read one approved file.", deep))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Tool input JSON Schema is invalid or exceeds its bounds.");
    }

    @Test
    void readsOnlyTheExactLegacyToolDefinitionContract() throws Exception {
        ObjectNode contract = JsonNodeFactory.instance.objectNode();
        contract.put("name", "read_file");
        contract.put("description", "Read one approved file.");
        contract.set("inputSchema", readFileSchema());
        contract.put("schemaDigest", READ_FILE_DIGEST);
        String prompt = ProviderToolDefinition.LEGACY_TOOL_PROMPT_PREFIX
                + new com.fasterxml.jackson.databind.ObjectMapper()
                        .writeValueAsString(JsonNodeFactory.instance.arrayNode().add(contract));

        assertThat(ProviderToolDefinition.legacyDefinitions(java.util.List.of(
                ProviderChatMessage.plain(ProviderChatMessage.Role.SYSTEM, prompt))))
                .singleElement()
                .satisfies(tool -> assertThat(tool.name()).isEqualTo("read_file"));

        String duplicateField = ProviderToolDefinition.LEGACY_TOOL_PROMPT_PREFIX
                + "[{\"name\":\"read_file\",\"name\":\"read_diff\","
                + "\"description\":\"x\",\"inputSchema\":{},"
                + "\"schemaDigest\":\"sha256:" + "0".repeat(64) + "\"}]";
        assertThatThrownBy(() -> ProviderToolDefinition.legacyDefinitions(java.util.List.of(
                ProviderChatMessage.plain(
                        ProviderChatMessage.Role.SYSTEM, duplicateField))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Declared tool definitions are invalid.");
    }

    private static ObjectNode readFileSchema() {
        ObjectNode input = JsonNodeFactory.instance.objectNode();
        input.put("type", "object");
        input.put("additionalProperties", false);
        input.putArray("required").add("path");
        input.putObject("properties").putObject("path").put("type", "string");
        return input;
    }
}

package org.urizo.axmodulestudio.backend.integration.ai.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class ProviderChatRequestTest {

    private static final Instant DEADLINE = Instant.parse("2026-08-31T12:00:00Z");

    @Test
    void keepsMessagesImmutableAndRedactsTheirContents() {
        var source = new java.util.ArrayList<>(List.of(
                ProviderChatMessage.plain(
                        ProviderChatMessage.Role.SYSTEM, "secret system fixture"),
                ProviderChatMessage.plain(
                        ProviderChatMessage.Role.USER, "secret user fixture")));
        ProviderChatRequest request = new ProviderChatRequest(
                ModelProvider.OPENAI, "fixture-model", source, DEADLINE);

        source.clear();

        assertThat(request.messages()).hasSize(2);
        assertThat(request.toString())
                .contains("messages=REDACTED")
                .doesNotContain("secret system fixture")
                .doesNotContain("secret user fixture");
        assertThat(request.messages().toString())
                .doesNotContain("secret system fixture")
                .doesNotContain("secret user fixture")
                .contains("content=REDACTED");
    }

    @Test
    void carriesTheRequestedResponseFormatAtTheProviderBoundary() {
        assertThat(ProviderChatRequest.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .contains("responseFormat");
    }

    @Test
    void toolCallArgumentsCountTowardTheExistingInputBound() {
        String oversizedArguments = "{\"value\":\"" + "a".repeat(65_536) + "\"}";
        ProviderChatMessage assistant = ProviderChatMessage.assistant(
                "",
                List.of(new ProviderChatMessage.ToolCall(
                        UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa").toString(),
                        "read_file",
                        oversizedArguments)));

        assertThatThrownBy(() -> new ProviderChatRequest(
                ModelProvider.OPENAI,
                "fixture-model",
                List.of(assistant),
                DEADLINE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("chat request exceeds its collection or size bounds");
    }

    @Test
    void convertsTheExistingDeclaredToolPromptIntoNativeDefinitions() throws Exception {
        ObjectNode contract = toolContract();
        String declaration = ProviderToolDefinition.LEGACY_TOOL_PROMPT_PREFIX
                + new ObjectMapper().writeValueAsString(
                        JsonNodeFactory.instance.arrayNode().add(contract));
        ProviderChatRequest request = new ProviderChatRequest(
                ModelProvider.OPENAI,
                "fixture-model",
                List.of(
                        ProviderChatMessage.plain(
                                ProviderChatMessage.Role.SYSTEM, declaration),
                        ProviderChatMessage.plain(
                                ProviderChatMessage.Role.USER, "Read the approved file.")),
                DEADLINE);

        assertThat(request.legacyToolEnvelope()).isTrue();
        assertThat(request.tools()).singleElement()
                .satisfies(tool -> assertThat(tool.name()).isEqualTo("read_file"));
        assertThat(request.providerMessages()).singleElement()
                .satisfies(message -> assertThat(message.content())
                        .isEqualTo("Read the approved file."));
        assertThat(request.toString())
                .contains("tools=1")
                .doesNotContain("Read the approved file");
    }

    @Test
    void rejectsUndeclaredHistoricalNativeToolCalls() {
        ProviderToolDefinition readDiff = new ProviderToolDefinition(
                "read_diff", "Read the approved diff.", readDiffSchema());
        ProviderChatMessage assistant = ProviderChatMessage.assistant(
                "",
                List.of(new ProviderChatMessage.ToolCall(
                        "77777777-7777-4777-8777-777777777777",
                        "read_file",
                        "{\"path\":\"README.md\"}")));

        assertThatThrownBy(() -> new ProviderChatRequest(
                ModelProvider.OPENAI,
                "fixture-model",
                List.of(assistant),
                List.of(readDiff),
                DEADLINE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Message references an undeclared tool.");
    }

    @Test
    void rejectsUnpairedOrMismatchedNativeToolHistory() {
        ProviderToolDefinition readFile = new ProviderToolDefinition(
                "read_file", "Read one approved file.", readFileSchema());
        ProviderChatMessage assistant = ProviderChatMessage.assistant(
                "",
                List.of(new ProviderChatMessage.ToolCall(
                        "77777777-7777-4777-8777-777777777777",
                        "read_file",
                        "{\"path\":\"README.md\"}")));

        assertThatThrownBy(() -> new ProviderChatRequest(
                ModelProvider.OPENAI,
                "fixture-model",
                List.of(assistant),
                List.of(readFile),
                DEADLINE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Assistant tool calls require matching tool results.");

        ProviderChatMessage mismatchedResult = ProviderChatMessage.tool(
                "88888888-8888-4888-8888-888888888888",
                "read_file",
                "{\"content\":\"fixture\"}");
        assertThatThrownBy(() -> new ProviderChatRequest(
                ModelProvider.OPENAI,
                "fixture-model",
                List.of(assistant, mismatchedResult),
                List.of(readFile),
                DEADLINE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Tool results must match pending assistant tool calls.");
    }

    private static ObjectNode toolContract() {
        ObjectNode contract = JsonNodeFactory.instance.objectNode();
        contract.put("name", "read_file");
        contract.put("description", "Read one approved file.");
        ObjectNode input = readFileSchema();
        contract.set("inputSchema", input);
        contract.put("schemaDigest", ProviderJsonSchema.digest(
                ProviderJsonSchema.validateAndCanonicalize(input)));
        return contract;
    }

    private static ObjectNode readFileSchema() {
        ObjectNode input = JsonNodeFactory.instance.objectNode();
        input.put("type", "object").put("additionalProperties", false);
        input.putArray("required").add("path");
        input.putObject("properties").putObject("path").put("type", "string");
        return input;
    }

    private static ObjectNode readDiffSchema() {
        ObjectNode input = JsonNodeFactory.instance.objectNode();
        input.put("type", "object").put("additionalProperties", false);
        input.putArray("required");
        input.putObject("properties");
        return input;
    }
}

package org.urizo.axmodulestudio.backend.coding.service;

import org.urizo.axmodulestudio.backend.coding.dto.CodingModelTurnContract;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ModelCapability;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ModelGatewayErrorCode;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ModelProvider;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderCapabilityPolicy;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderCapabilityRegistry;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderChatGatewayPort;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderChatMessage;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderChatRequest;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderChatResponse;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderGatewayException;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderLane;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderModelRegistration;

class CodingModelTurnServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-11T08:00:00Z");
    private static final String MODEL = "local-google-chat-model";

    private final ProviderChatGatewayPort gateway = mock(ProviderChatGatewayPort.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private CodingModelTurnService service;

    @BeforeEach
    void configureService() {
        ProviderModelRegistration google = new ProviderModelRegistration(
                ModelProvider.GOOGLE_GENAI,
                MODEL,
                Set.of(ModelCapability.CHAT),
                Duration.ofSeconds(30),
                2);
        service = new CodingModelTurnService(
                registry(List.of(google)),
                gateway,
                objectMapper,
                Clock.fixed(NOW, ZoneOffset.UTC),
                false);
    }

    @Test
    void routesChatTextThroughTheSpringOwnedProviderSelection() {
        CodingModelTurnContract.Request request = chatRequest();
        when(gateway.chat(any())).thenReturn(new ProviderChatResponse(
                ModelProvider.GOOGLE_GENAI,
                MODEL,
                "local response fixture",
                8,
                3,
                Duration.ofMillis(25)));

        CodingModelTurnContract.Response response = service.execute(request);

        ArgumentCaptor<ProviderChatRequest> routed = ArgumentCaptor.forClass(ProviderChatRequest.class);
        verify(gateway).chat(routed.capture());
        assertThat(routed.getValue().provider()).isEqualTo(ModelProvider.GOOGLE_GENAI);
        assertThat(routed.getValue().modelId()).isEqualTo(MODEL);
        assertThat(routed.getValue().messages())
                .extracting(ProviderChatMessage::role)
                .containsExactly(
                        ProviderChatMessage.Role.SYSTEM,
                        ProviderChatMessage.Role.USER);
        assertThat(routed.getValue().prompt()).isEqualTo(
                "[system]\nStay in scope.\n\n[user]\nSummarize the approved contract.");
        assertThat(routed.getValue().toString()).doesNotContain("Stay in scope").contains("REDACTED");
        assertThat(response.schemaVersion()).isEqualTo("1.0");
        assertThat(response.turnId()).isEqualTo(request.turnId());
        assertThat(response.jobId()).isEqualTo(request.jobId());
        assertThat(response.traceId()).isEqualTo(request.traceId());
        assertThat(response.idempotencyKey()).isEqualTo(request.idempotencyKey());
        assertThat(response.selectedModel().provider()).isEqualTo("GOOGLE");
        assertThat(response.usage().totalTokens()).isEqualTo(11);
        assertThat(response.finishReason()).isEqualTo("STOP");
        assertThat(response.toolCalls()).isEmpty();
        assertThat(response.toString()).doesNotContain("local response fixture").contains("REDACTED");
    }

    @Test
    void rejectsToolCallingWithoutInvokingAProvider() {
        CodingModelTurnContract.Request chat = chatRequest();
        CodingModelTurnContract.Request toolRequest = new CodingModelTurnContract.Request(
                chat.schemaVersion(), chat.turnId(), chat.jobId(), chat.traceId(), chat.idempotencyKey(),
                chat.attempt(), chat.expectedStateVersion(), chat.nodeName(), chat.promptVersion(),
                chat.contextDigest(), List.of("CHAT", "TOOL_CALLING"), chat.messages(),
                List.of(invalidToolSchema()),
                chat.responseFormat(), chat.deadlineAt());

        assertThatThrownBy(() -> service.execute(toolRequest))
                .isInstanceOfSatisfying(ProviderGatewayException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(ModelGatewayErrorCode.MODEL_CAPABILITY_UNSUPPORTED);
                    assertThat(failure.getMessage()).doesNotContain("read_file");
                });
        verify(gateway, never()).chat(any());
    }

    @Test
    void emitsOneDeterministicReadFileCandidateForTheExplicitLocalMockProfile() {
        ProviderModelRegistration google = new ProviderModelRegistration(
                ModelProvider.GOOGLE_GENAI,
                MODEL,
                Set.of(ModelCapability.CHAT),
                Duration.ofSeconds(30),
                2);
        CodingModelTurnService localMockService = new CodingModelTurnService(
                registry(List.of(google)), gateway, objectMapper,
                Clock.fixed(NOW, ZoneOffset.UTC), true);
        CodingModelTurnContract.Request chat = chatRequest();
        CodingModelTurnContract.Request toolRequest = new CodingModelTurnContract.Request(
                chat.schemaVersion(), chat.turnId(), chat.jobId(), chat.traceId(), chat.idempotencyKey(),
                chat.attempt(), chat.expectedStateVersion(), chat.nodeName(), chat.promptVersion(),
                chat.contextDigest(), List.of("CHAT", "TOOL_CALLING"), chat.messages(),
                List.of(toolSchema()), chat.responseFormat(), chat.deadlineAt());
        when(gateway.chat(any())).thenReturn(new ProviderChatResponse(
                ModelProvider.GOOGLE_GENAI,
                MODEL,
                "safe local planning response",
                8,
                3,
                Duration.ZERO));

        CodingModelTurnContract.Response response = localMockService.execute(toolRequest);

        assertThat(response.finishReason()).isEqualTo("TOOL_CALLS");
        assertThat(response.toolCalls()).hasSize(1);
        assertThat(response.toolCalls().get(0).name()).isEqualTo("read_file");
        assertThat(response.toolCalls().get(0).arguments())
                .isEqualTo(JsonNodeFactory.instance.objectNode().put("path", "README.md"));
    }

    @Test
    void repairsOneFencedApprovedProviderToolCallAndKeepsTheStrictEnvelope() {
        ProviderModelRegistration google = new ProviderModelRegistration(
                ModelProvider.GOOGLE_GENAI,
                MODEL,
                Set.of(ModelCapability.CHAT, ModelCapability.TOOL_CALLING),
                Duration.ofSeconds(30),
                2);
        CodingModelTurnService providerToolService = new CodingModelTurnService(
                registry(List.of(google)), gateway, objectMapper,
                Clock.fixed(NOW, ZoneOffset.UTC), false);
        CodingModelTurnContract.Request chat = chatRequest();
        CodingModelTurnContract.Request toolRequest = new CodingModelTurnContract.Request(
                chat.schemaVersion(), chat.turnId(), chat.jobId(), chat.traceId(), chat.idempotencyKey(),
                chat.attempt(), chat.expectedStateVersion(), chat.nodeName(), chat.promptVersion(),
                chat.contextDigest(), List.of("CHAT", "TOOL_CALLING"), chat.messages(),
                List.of(toolSchema()), chat.responseFormat(), chat.deadlineAt());
        when(gateway.chat(any())).thenReturn(new ProviderChatResponse(
                ModelProvider.GOOGLE_GENAI,
                MODEL,
                "Here is the result.\n```json\n"
                        + "{\"assistant\":\"\",\"toolCalls\":[{\"name\":\"read_file\","
                        + "\"arguments\":{\"path\":\"src/App.java\"}}]}\n```",
                8,
                3,
                Duration.ZERO));

        CodingModelTurnContract.Response response = providerToolService.execute(toolRequest);

        assertThat(response.finishReason()).isEqualTo("TOOL_CALLS");
        assertThat(response.toolCalls()).singleElement().satisfies(call -> {
            assertThat(call.name()).isEqualTo("read_file");
            assertThat(call.arguments().path("path").asText()).isEqualTo("src/App.java");
        });
        ArgumentCaptor<ProviderChatRequest> routed =
                ArgumentCaptor.forClass(ProviderChatRequest.class);
        verify(gateway).chat(routed.capture());
        assertThat(routed.getValue().messages())
                .extracting(ProviderChatMessage::role)
                .containsExactly(
                        ProviderChatMessage.Role.SYSTEM,
                        ProviderChatMessage.Role.SYSTEM,
                        ProviderChatMessage.Role.USER);
        assertThat(routed.getValue().messages().get(0).content())
                .startsWith("Return exactly one JSON object")
                .contains("Declared tools:");
    }

    @Test
    void preservesAssistantToolAndToolResultRolesAcrossProviderTurns() {
        ProviderModelRegistration google = new ProviderModelRegistration(
                ModelProvider.GOOGLE_GENAI,
                MODEL,
                Set.of(ModelCapability.CHAT, ModelCapability.TOOL_CALLING),
                Duration.ofSeconds(30),
                2);
        CodingModelTurnService providerToolService = new CodingModelTurnService(
                registry(List.of(google)), gateway, objectMapper,
                Clock.fixed(NOW, ZoneOffset.UTC), false);
        CodingModelTurnContract.Request chat = chatRequest();
        String toolCallId = "77777777-7777-4777-8777-777777777777";
        ObjectNode assistant = objectMapper.createObjectNode()
                .put("role", "assistant")
                .put("content", "");
        assistant.putArray("toolCalls").addObject()
                .put("toolCallId", toolCallId)
                .put("name", "read_file")
                .set("arguments", objectMapper.createObjectNode().put("path", "README.md"));
        ObjectNode tool = objectMapper.createObjectNode()
                .put("role", "tool")
                .put("toolCallId", toolCallId)
                .put("executionId", "88888888-8888-4888-8888-888888888888")
                .put("content", "{\"content\":\"fixture\"}");
        tool.putObject("result")
                .put("mediaType", "application/json")
                .put("resultRef", "/internal/coding/tool-executions/fixture/result")
                .put("sizeBytes", 21)
                .put("digest", "sha256:" + "c".repeat(64));
        CodingModelTurnContract.Request toolRequest = new CodingModelTurnContract.Request(
                chat.schemaVersion(), chat.turnId(), chat.jobId(), chat.traceId(), chat.idempotencyKey(),
                chat.attempt(), chat.expectedStateVersion(), chat.nodeName(), chat.promptVersion(),
                chat.contextDigest(), List.of("CHAT", "TOOL_CALLING"),
                List.of(chat.messages().get(0), chat.messages().get(1), assistant, tool),
                List.of(toolSchema()), chat.responseFormat(), chat.deadlineAt());
        when(gateway.chat(any())).thenReturn(new ProviderChatResponse(
                ModelProvider.GOOGLE_GENAI,
                MODEL,
                "{\"assistant\":\"done\",\"toolCalls\":[]}",
                8,
                3,
                Duration.ZERO));

        providerToolService.execute(toolRequest);

        ArgumentCaptor<ProviderChatRequest> routed =
                ArgumentCaptor.forClass(ProviderChatRequest.class);
        verify(gateway).chat(routed.capture());
        assertThat(routed.getValue().messages())
                .extracting(ProviderChatMessage::role)
                .containsExactly(
                        ProviderChatMessage.Role.SYSTEM,
                        ProviderChatMessage.Role.SYSTEM,
                        ProviderChatMessage.Role.USER,
                        ProviderChatMessage.Role.ASSISTANT,
                        ProviderChatMessage.Role.TOOL);
        assertThat(routed.getValue().messages().get(3).toolCalls())
                .singleElement()
                .satisfies(call -> {
                    assertThat(call.id()).isEqualTo(toolCallId);
                    assertThat(call.name()).isEqualTo("read_file");
                });
        assertThat(routed.getValue().messages().get(4).toolCallId())
                .isEqualTo(toolCallId);
    }

    @Test
    void rejectsAnEnvelopeThatIsStillInvalidAfterOneRepair() {
        ProviderModelRegistration google = new ProviderModelRegistration(
                ModelProvider.GOOGLE_GENAI,
                MODEL,
                Set.of(ModelCapability.CHAT, ModelCapability.TOOL_CALLING),
                Duration.ofSeconds(30),
                2);
        CodingModelTurnService providerToolService = new CodingModelTurnService(
                registry(List.of(google)), gateway, objectMapper,
                Clock.fixed(NOW, ZoneOffset.UTC), false);
        CodingModelTurnContract.Request chat = chatRequest();
        CodingModelTurnContract.Request toolRequest = new CodingModelTurnContract.Request(
                chat.schemaVersion(), chat.turnId(), chat.jobId(), chat.traceId(), chat.idempotencyKey(),
                chat.attempt(), chat.expectedStateVersion(), chat.nodeName(), chat.promptVersion(),
                chat.contextDigest(), List.of("CHAT", "TOOL_CALLING"), chat.messages(),
                List.of(toolSchema()), chat.responseFormat(), chat.deadlineAt());
        when(gateway.chat(any())).thenReturn(new ProviderChatResponse(
                ModelProvider.GOOGLE_GENAI,
                MODEL,
                "prefix {\"assistant\":\"ok\",\"toolCalls\":[]} "
                        + "{\"assistant\":\"second\",\"toolCalls\":[]}",
                8,
                3,
                Duration.ZERO));

        assertThatThrownBy(() -> providerToolService.execute(toolRequest))
                .isInstanceOfSatisfying(ProviderGatewayException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(ModelGatewayErrorCode.MODEL_RESPONSE_INVALID));
    }

    @Test
    void failsClosedWhenNoConfiguredModelCanSatisfyChat() {
        CodingModelTurnService empty = new CodingModelTurnService(
                registry(List.of()), gateway, objectMapper,
                Clock.fixed(NOW, ZoneOffset.UTC), false);

        assertThatThrownBy(() -> empty.execute(chatRequest()))
                .isInstanceOfSatisfying(ProviderGatewayException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ModelGatewayErrorCode.MODEL_NOT_CONFIGURED));
        verify(gateway, never()).chat(any());
    }

    @Test
    void requestDiagnosticsRedactMessagesAndToolSchemas() {
        CodingModelTurnContract.Request request = chatRequest();

        assertThat(request.toString())
                .doesNotContain("Stay in scope")
                .doesNotContain("Summarize the approved contract")
                .contains("messages=REDACTED")
                .contains("toolSchemas=REDACTED");
    }

    private static ProviderCapabilityRegistry registry(List<ProviderModelRegistration> registrations) {
        return new ProviderCapabilityRegistry(
                ProviderLane.PRODUCT,
                ProviderCapabilityPolicy.stage2Baseline(),
                registrations);
    }

    private static CodingModelTurnContract.Request chatRequest() {
        JsonNode system = JsonNodeFactory.instance.objectNode()
                .put("role", "system")
                .put("content", "Stay in scope.");
        JsonNode user = JsonNodeFactory.instance.objectNode()
                .put("role", "user")
                .put("content", "Summarize the approved contract.");
        return new CodingModelTurnContract.Request(
                "1.0",
                UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
                UUID.fromString("55555555-5555-4555-8555-555555555555"),
                UUID.fromString("66666666-6666-4666-8666-666666666666"),
                "stage4.model.turn.0001",
                1,
                4,
                "plan",
                "coding-plan-v1",
                "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                List.of("CHAT"),
                List.of(system, user),
                List.of(),
                JsonNodeFactory.instance.objectNode().put("type", "TEXT"),
                NOW.plusSeconds(60));
    }

    private static JsonNode toolSchema() {
        var toolSchema = JsonNodeFactory.instance.objectNode()
                .put("name", "read_file")
                .put("description", "Read one approved relative file.")
                .put("schemaDigest",
                        "sha256:39b714704935190561ed407980480b9a4a0b346b97346e0bff71fb9ace820194");
        var inputSchema = JsonNodeFactory.instance.objectNode()
                .put("type", "object")
                .put("additionalProperties", false);
        inputSchema.set("required", JsonNodeFactory.instance.arrayNode().add("path"));
        inputSchema.set("properties", JsonNodeFactory.instance.objectNode()
                .set("path", JsonNodeFactory.instance.objectNode().put("type", "string")));
        toolSchema.set("inputSchema", inputSchema);
        return toolSchema;
    }

    private static JsonNode invalidToolSchema() {
        ObjectNode schema = (ObjectNode) toolSchema().deepCopy();
        schema.put("name", "arbitrary_shell");
        return schema;
    }
}

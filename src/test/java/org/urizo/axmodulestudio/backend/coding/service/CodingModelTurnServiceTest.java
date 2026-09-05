package org.urizo.axmodulestudio.backend.coding.service;

import org.urizo.axmodulestudio.backend.coding.dto.CodingModelTurnContract;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderResponseFormat;

class CodingModelTurnServiceTest {


    private static final Instant NOW = Instant.parse("2026-08-11T08:00:00Z");
    private static final String MODEL = "local-google-chat-model";
    private static final String TOOL_CALL_ONE = "11111111-1111-4111-8111-111111111111";
    private static final String TOOL_CALL_TWO = "22222222-2222-4222-8222-222222222222";
    private static final String TOOL_CALL_THREE = "33333333-3333-4333-8333-333333333333";
    private static final String EXECUTION_ONE = "44444444-4444-4444-8444-444444444444";
    private static final String EXECUTION_TWO = "77777777-7777-4777-8777-777777777777";
    private static final String EXECUTION_THREE = "88888888-8888-4888-8888-888888888888";

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
    void carriesNativeToolDefinitionsAndReturnsTheNativeProviderToolCall() {
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
                "",
                List.of(new ProviderChatMessage.ToolCall(
                        "99999999-9999-4999-8999-999999999999",
                        "read_file",
                        "{\"path\":\"src/App.java\"}")),
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
                        ProviderChatMessage.Role.USER);
        assertThat(routed.getValue().messages().get(0).content())
                .isEqualTo("Stay in scope.");
        assertThat(routed.getValue().tools()).singleElement().satisfies(tool -> {
            assertThat(tool.name()).isEqualTo("read_file");
            assertThat(tool.schemaDigest()).isEqualTo(
                    "sha256:39b714704935190561ed407980480b9a4a0b346b97346e0bff71fb9ace820194");
        });
    }

    @Test
    void preservesTheNativeToolExchangeForTheProvider() {
        // The provider path declares its tools natively through the conversion trigger,
        // so the recorded exchange replays as native assistant tool calls and tool
        // results instead of being collapsed to text.
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
                "done",
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
                        ProviderChatMessage.Role.USER,
                        ProviderChatMessage.Role.ASSISTANT,
                        ProviderChatMessage.Role.TOOL);
        assertThat(routed.getValue().messages().get(2).toolCalls())
                .singleElement()
                .satisfies(call -> {
                    assertThat(call.id()).isEqualTo(toolCallId);
                    assertThat(call.name()).isEqualTo("read_file");
                });
        assertThat(routed.getValue().messages().get(3).toolCallId())
                .isEqualTo(toolCallId);
    }

    @Test
    void preservesJsonSchemaThroughTheProviderRequestAndStructuredResponse() {
        ProviderModelRegistration google = new ProviderModelRegistration(
                ModelProvider.GOOGLE_GENAI,
                MODEL,
                Set.of(ModelCapability.CHAT, ModelCapability.STRUCTURED_OUTPUT),
                Duration.ofSeconds(30),
                2);
        CodingModelTurnService structuredService = new CodingModelTurnService(
                registry(List.of(google)), gateway, objectMapper,
                Clock.fixed(NOW, ZoneOffset.UTC), false);
        CodingModelTurnContract.Request request = structuredRequest();
        when(gateway.chat(any())).thenReturn(new ProviderChatResponse(
                ModelProvider.GOOGLE_GENAI,
                MODEL,
                "```json\n{\"port\":\"feasible\",\"payload\":{\"summary\":\"ok\"}}\n```",
                8,
                3,
                Duration.ZERO));

        CodingModelTurnContract.Response response = structuredService.execute(request);

        ArgumentCaptor<ProviderChatRequest> routed =
                ArgumentCaptor.forClass(ProviderChatRequest.class);
        verify(gateway).chat(routed.capture());
        assertThat(routed.getValue().responseFormat().type())
                .isEqualTo(ProviderResponseFormat.Type.JSON_SCHEMA);
        assertThat(routed.getValue().responseFormat().schemaDigest())
                .isEqualTo(request.responseFormat().path("schemaDigest").asText());
        assertThat(routed.getValue().responseFormat().outputSchema())
                .isEqualTo(request.responseFormat().path("outputSchema"));
        JsonNode resultFormat = objectMapper.valueToTree(response.responseFormat());
        assertThat(resultFormat.path("type").asText()).isEqualTo("JSON_SCHEMA");
        assertThat(resultFormat.path("schemaDigest").asText())
                .isEqualTo(request.responseFormat().path("schemaDigest").asText());
        assertThat(resultFormat.path("structuredOutput").path("port").asText())
                .isEqualTo("feasible");
        assertThat(resultFormat.path("structuredOutput").path("payload")
                .path("summary").asText()).isEqualTo("ok");
        assertThat(response.assistant().content()).isEmpty();
    }

    @Test
    void rejectsMalformedNativeToolArguments() {
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
                "",
                List.of(new ProviderChatMessage.ToolCall(
                        "99999999-9999-4999-8999-999999999999",
                        "read_file",
                        "{\"path\":\"README.md\",\"path\":\"other\"}")),
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
    void usesTheBoundProviderOrderAndFallsBackOnlyAfterATransientFailure() {
        ProviderModelRegistration primary = new ProviderModelRegistration(
                ModelProvider.OPENAI,
                "openai-bound-model",
                Set.of(ModelCapability.CHAT),
                Duration.ofSeconds(30),
                2);
        ProviderModelRegistration fallback = new ProviderModelRegistration(
                ModelProvider.GOOGLE_GENAI,
                "gemini-bound-model",
                Set.of(ModelCapability.CHAT),
                Duration.ofSeconds(30),
                2);
        CodingModelTurnService bound = new CodingModelTurnService(
                registry(List.of(primary, fallback)), gateway, objectMapper,
                Clock.fixed(NOW, ZoneOffset.UTC), false);
        when(gateway.chat(any()))
                .thenThrow(new ProviderGatewayException(
                        ModelGatewayErrorCode.MODEL_TIMEOUT,
                        "The primary provider timed out."))
                .thenReturn(new ProviderChatResponse(
                        ModelProvider.GOOGLE_GENAI,
                        "gemini-bound-model",
                        "fallback response",
                        2,
                        1,
                        Duration.ofMillis(10)));

        CodingModelTurnContract.Response response = bound.execute(
                chatRequest(), List.of(primary, fallback));

        ArgumentCaptor<ProviderChatRequest> routed =
                ArgumentCaptor.forClass(ProviderChatRequest.class);
        verify(gateway, times(2)).chat(routed.capture());
        assertThat(routed.getAllValues())
                .extracting(ProviderChatRequest::provider, ProviderChatRequest::modelId)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                ModelProvider.OPENAI, "openai-bound-model"),
                        org.assertj.core.groups.Tuple.tuple(
                                ModelProvider.GOOGLE_GENAI, "gemini-bound-model"));
        assertThat(response.selectedModel().provider()).isEqualTo("GOOGLE");
        assertThat(response.selectedModel().modelId()).isEqualTo("gemini-bound-model");
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

    @Test
    void keepsTheToolBodyWhenTheContextFitsTheRequestBudget() {
        CodingModelTurnContract.Request request = providerToolRequest(List.of("AAAA"));
        when(gateway.chat(any())).thenReturn(toolEnvelopeResponse());

        providerToolService().execute(request);

        // Text results ride in a one-field JSON object on the native path.
        assertThat(toolContents()).containsExactly("{\"content\":\"AAAA\"}");
    }

    @Test
    void dropsTheOldestToolBodiesWhenTheContextExceedsTheRequestBudget() {
        CodingModelTurnContract.Request request = providerToolRequest(List.of(
                "A".repeat(25_000), "B".repeat(25_000), "C".repeat(25_000)));
        when(gateway.chat(any())).thenReturn(toolEnvelopeResponse());

        providerToolService().execute(request);

        ArgumentCaptor<ProviderChatRequest> routed =
                ArgumentCaptor.forClass(ProviderChatRequest.class);
        verify(gateway).chat(routed.capture());
        long characters = routed.getValue().messages().stream()
                .mapToLong(message -> message.content().length())
                .sum();
        assertThat(characters).isLessThanOrEqualTo(65_536);
        List<String> contents = routed.getValue().messages().stream()
                .filter(message -> message.role() == ProviderChatMessage.Role.TOOL)
                .map(ProviderChatMessage::content)
                .toList();
        assertThat(contents).hasSize(3);
        assertThat(contents.get(0)).doesNotContain("AAAA").contains("elided");
        assertThat(contents.get(1)).contains("B".repeat(25_000));
        assertThat(contents.get(2)).contains("C".repeat(25_000));
    }

    /**
     * The two tests below carry the same messages and differ only in how large the tool
     * definition is. Measuring the messages alone left a band where nothing was elided and
     * ProviderChatRequest still refused the request on construction.
     */
    private static final String NEAR_BUDGET_TOOL_BODY = "A".repeat(65_000);
    /** The contract caps a tool description at 2,000 characters; this fills it. */
    private static final int MAX_DESCRIPTION_PADDING = 1_968;

    @Test
    void keepsTheToolBodyWhenTheMessagesAndToolDefinitionsBothFit() {
        CodingModelTurnContract.Request request =
                providerToolRequest(List.of(NEAR_BUDGET_TOOL_BODY));
        when(gateway.chat(any())).thenReturn(toolEnvelopeResponse());

        providerToolService().execute(request);

        assertThat(toolContents().get(0)).contains(NEAR_BUDGET_TOOL_BODY);
    }

    @Test
    void elidesWhenTheToolDefinitionsPushFittingMessagesOverTheBudget() {
        CodingModelTurnContract.Request request =
                providerToolRequest(List.of(NEAR_BUDGET_TOOL_BODY), MAX_DESCRIPTION_PADDING);
        when(gateway.chat(any())).thenReturn(toolEnvelopeResponse());

        // Before the overhead was reserved this threw from the ProviderChatRequest constructor,
        // because nothing was elided and the tool definitions were counted only there.
        providerToolService().execute(request);

        ArgumentCaptor<ProviderChatRequest> routed =
                ArgumentCaptor.forClass(ProviderChatRequest.class);
        verify(gateway).chat(routed.capture());
        long messageCharacters = routed.getValue().messages().stream()
                .mapToLong(message -> message.content().length()
                        + message.toolCalls().stream()
                                .mapToLong(call -> call.arguments().length())
                                .sum())
                .sum();
        long toolCharacters = routed.getValue().tools().stream()
                .mapToLong(tool -> tool.name().length()
                        + tool.description().length()
                        + tool.providerInputSchema().length())
                .sum();
        assertThat(toolCharacters).isGreaterThan(2_000);
        assertThat(messageCharacters + toolCharacters).isLessThanOrEqualTo(65_536);
        List<String> contents = routed.getValue().messages().stream()
                .filter(message -> message.role() == ProviderChatMessage.Role.TOOL)
                .map(ProviderChatMessage::content)
                .toList();
        assertThat(contents.get(0)).doesNotContain("AAAA").contains("elided");
    }

    @Test
    void failsAsAGatewayErrorWhenNoToolBodyCanBeDropped() {
        CodingModelTurnContract.Request chat = chatRequest();
        ObjectNode user = objectMapper.createObjectNode()
                .put("role", "user")
                .put("content", "D".repeat(70_000));
        CodingModelTurnContract.Request request = new CodingModelTurnContract.Request(
                chat.schemaVersion(), chat.turnId(), chat.jobId(), chat.traceId(),
                chat.idempotencyKey(), chat.attempt(), chat.expectedStateVersion(),
                chat.nodeName(), chat.promptVersion(), chat.contextDigest(),
                chat.requiredCapabilities(), List.of(chat.messages().get(0), user),
                chat.toolSchemas(), chat.responseFormat(), chat.deadlineAt());

        assertThatThrownBy(() -> service.execute(request))
                .isInstanceOfSatisfying(ProviderGatewayException.class, failure ->
                        assertThat(failure.code())
                                .isEqualTo(ModelGatewayErrorCode.MODEL_CAPABILITY_UNSUPPORTED));
        verify(gateway, never()).chat(any());
    }

    private CodingModelTurnService providerToolService() {
        ProviderModelRegistration google = new ProviderModelRegistration(
                ModelProvider.GOOGLE_GENAI,
                MODEL,
                Set.of(ModelCapability.CHAT, ModelCapability.TOOL_CALLING),
                Duration.ofSeconds(30),
                2);
        return new CodingModelTurnService(
                registry(List.of(google)), gateway, objectMapper,
                Clock.fixed(NOW, ZoneOffset.UTC), false);
    }

    private ProviderChatResponse toolEnvelopeResponse() {
        return new ProviderChatResponse(
                ModelProvider.GOOGLE_GENAI, MODEL,
                "{\"assistant\":\"done\",\"toolCalls\":[]}", 8, 3, Duration.ZERO);
    }

    private List<String> toolContents() {
        ArgumentCaptor<ProviderChatRequest> routed =
                ArgumentCaptor.forClass(ProviderChatRequest.class);
        verify(gateway).chat(routed.capture());
        return routed.getValue().messages().stream()
                .filter(message -> message.role() == ProviderChatMessage.Role.TOOL)
                .map(ProviderChatMessage::content)
                .toList();
    }

    /** One assistant tool call and its tool result per body, in the order given. */
    private CodingModelTurnContract.Request providerToolRequest(List<String> toolBodies) {
        return providerToolRequest(toolBodies, 0);
    }

    private CodingModelTurnContract.Request providerToolRequest(
            List<String> toolBodies, int descriptionPadding) {
        CodingModelTurnContract.Request chat = chatRequest();
        List<JsonNode> messages = new java.util.ArrayList<>(
                List.of(chat.messages().get(0), chat.messages().get(1)));
        for (int index = 0; index < toolBodies.size(); index++) {
            String toolCallId = (index + 1) + "1111111-1111-4111-8111-111111111111";
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
                    .put("executionId", (index + 1) + "2222222-2222-4222-8222-222222222222")
                    .put("content", toolBodies.get(index));
            tool.putObject("result")
                    .put("mediaType", "application/json")
                    .put("resultRef", "/internal/coding/tool-executions/fixture/result")
                    .put("sizeBytes", toolBodies.get(index).length())
                    .put("digest", "sha256:" + "c".repeat(64));
            messages.add(assistant);
            messages.add(tool);
        }
        return new CodingModelTurnContract.Request(
                chat.schemaVersion(), chat.turnId(), chat.jobId(), chat.traceId(),
                chat.idempotencyKey(), chat.attempt(), chat.expectedStateVersion(),
                chat.nodeName(), chat.promptVersion(), chat.contextDigest(),
                List.of("CHAT", "TOOL_CALLING"), List.copyOf(messages),
                List.of(toolSchema(descriptionPadding)), chat.responseFormat(), chat.deadlineAt());
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

    private static CodingModelTurnContract.Request structuredRequest() {
        CodingModelTurnContract.Request chat = chatRequest();
        ObjectNode schema = JsonNodeFactory.instance.objectNode()
                .put("type", "object")
                .put("additionalProperties", false);
        schema.putArray("required").add("port").add("payload");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("port").put("type", "string");
        ObjectNode payload = properties.putObject("payload")
                .put("type", "object")
                .put("additionalProperties", false);
        payload.putArray("required").add("summary");
        payload.putObject("properties").putObject("summary").put("type", "string");
        ObjectNode format = ProviderResponseFormat.jsonSchema(schema).requestContract();
        return new CodingModelTurnContract.Request(
                chat.schemaVersion(), chat.turnId(), chat.jobId(), chat.traceId(),
                chat.idempotencyKey(), chat.attempt(), chat.expectedStateVersion(),
                chat.nodeName(), chat.promptVersion(), chat.contextDigest(),
                List.of("CHAT", "STRUCTURED_OUTPUT"), chat.messages(), List.of(),
                format, chat.deadlineAt());
    }

    private static JsonNode toolSchema() {
        return toolSchema(0);
    }

    private static JsonNode toolSchema(int descriptionPadding) {
        var toolSchema = JsonNodeFactory.instance.objectNode()
                .put("name", "read_file")
                .put("description",
                        "Read one approved relative file." + " ".repeat(descriptionPadding))
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

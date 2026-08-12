package org.urizo.axmodulestudio.backend.coding.modelturn;

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
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.urizo.axmodulestudio.backend.ai.gateway.ModelCapability;
import org.urizo.axmodulestudio.backend.ai.gateway.ModelGatewayErrorCode;
import org.urizo.axmodulestudio.backend.ai.gateway.ModelProvider;
import org.urizo.axmodulestudio.backend.ai.gateway.ProviderCapabilityPolicy;
import org.urizo.axmodulestudio.backend.ai.gateway.ProviderCapabilityRegistry;
import org.urizo.axmodulestudio.backend.ai.gateway.ProviderChatGatewayPort;
import org.urizo.axmodulestudio.backend.ai.gateway.ProviderChatRequest;
import org.urizo.axmodulestudio.backend.ai.gateway.ProviderChatResponse;
import org.urizo.axmodulestudio.backend.ai.gateway.ProviderGatewayException;
import org.urizo.axmodulestudio.backend.ai.gateway.ProviderLane;
import org.urizo.axmodulestudio.backend.ai.gateway.ProviderModelRegistration;

class CodingModelTurnServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-11T08:00:00Z");
    private static final String MODEL = "local-google-chat-model";

    private final ProviderChatGatewayPort gateway = mock(ProviderChatGatewayPort.class);
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
                List.of(toolSchema()),
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
                registry(List.of(google)), gateway, Clock.fixed(NOW, ZoneOffset.UTC), true);
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
    void failsClosedWhenNoConfiguredModelCanSatisfyChat() {
        CodingModelTurnService empty = new CodingModelTurnService(
                registry(List.of()), gateway, Clock.fixed(NOW, ZoneOffset.UTC), false);

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
}

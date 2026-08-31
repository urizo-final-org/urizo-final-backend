package org.urizo.axmodulestudio.backend.coding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.urizo.axmodulestudio.backend.coding.dto.CodingHandlerContract;
import org.urizo.axmodulestudio.backend.coding.dto.CodingModelTurnContract;
import org.urizo.axmodulestudio.backend.coding.dto.CodingModelTurnPermit;
import org.urizo.axmodulestudio.backend.coding.dto.CodingToolContract;
import org.urizo.axmodulestudio.backend.coding.repository.CodingModelTurnGuard;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ModelCapability;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ModelProvider;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderCapabilityPolicy;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderCapabilityRegistry;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderChatGatewayPort;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderChatRequest;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderChatResponse;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderLane;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderModelRegistration;

class CodingHandlerStageServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-11T08:00:00Z");
    private static final UUID JOB = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final UUID TRACE = UUID.fromString("66666666-6666-4666-8666-666666666666");
    private static final UUID RESULT = UUID.fromString("77777777-7777-4777-8777-777777777777");
    private static final UUID WORKSPACE = UUID.fromString("88888888-8888-4888-8888-888888888888");
    private static final UUID EXECUTION = UUID.fromString("99999999-9999-4999-8999-999999999999");
    private static final String BASE_SHA = "sha1:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String DIFF_DIGEST =
            "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";

    @Test
    void anEmptySnapshotAndStageToolIntersectionExposesNoTools() {
        assertThat(CodingHandlerStageService.allowedTools(
                Set.of("apply_patch"), Set.of("read_diff"))).isEmpty();
    }

    @Test
    void boundCodingPolicyDecodeFailsClosedForMissingOrUnknownTools() {
        ObjectMapper mapper = new ObjectMapper();
        CodingToolService.RuntimePolicy valid = CodingToolService.decodeRuntimePolicy(
                mapper,
                "{\"toolPolicy\":{\"allowedTools\":[\"read_diff\"]},"
                        + "\"guardrailProfileKey\":\"central.default\"}");
        assertThat(valid.allowedTools()).containsExactly("read_diff");

        for (String invalid : List.of(
                "{\"toolPolicy\":{},\"guardrailProfileKey\":\"central.default\"}",
                "{\"toolPolicy\":{\"allowedTools\":[\"shell_anything\"]},"
                        + "\"guardrailProfileKey\":\"central.default\"}")) {
            assertThatThrownBy(() -> CodingToolService.decodeRuntimePolicy(mapper, invalid))
                    .isInstanceOfSatisfying(CodingToolException.class,
                            failure -> assertThat(failure.code()).isEqualTo("TOOL_NOT_ALLOWED"));
        }
    }

    @Test
    void acceptsAFencedStageResultAndRejectsAnUnrepairableOne() {
        ObjectMapper mapper = new ObjectMapper();
        CodingHandlerResultService resultService = mock(CodingHandlerResultService.class);
        CodingToolService toolService = mock(CodingToolService.class);
        CodingModelTurnGuard guard = mock(CodingModelTurnGuard.class);
        ProviderChatGatewayPort gateway = mock(ProviderChatGatewayPort.class);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        ProviderModelRegistration registration = new ProviderModelRegistration(
                ModelProvider.GOOGLE_GENAI,
                "coding-test-model",
                Set.of(ModelCapability.CHAT, ModelCapability.TOOL_CALLING),
                Duration.ofSeconds(30),
                2);
        CodingModelTurnService modelService = new CodingModelTurnService(
                new ProviderCapabilityRegistry(
                        ProviderLane.PRODUCT,
                        ProviderCapabilityPolicy.stage2Baseline(),
                        List.of(registration)),
                gateway,
                mapper,
                clock,
                false);
        CodingHandlerStageService service = new CodingHandlerStageService(
                resultService, toolService, guard, modelService, mapper, clock);
        CodingToolService.StageAuthority authority = new CodingToolService.StageAuthority(
                TRACE,
                4,
                UUID.fromString("11111111-1111-4111-8111-111111111111"),
                UUID.fromString("22222222-2222-4222-8222-222222222222"),
                UUID.fromString("33333333-3333-4333-8333-333333333333"),
                UUID.fromString("44444444-4444-4444-8444-444444444444"),
                "coding",
                BASE_SHA,
                "sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",
                "sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
                "coding-v1",
                Set.of("CHAT", "TOOL_CALLING"),
                Set.of("coding"),
                Set.copyOf(CodingToolService.CODING_TOOL_SCHEMA_DIGESTS.keySet()),
                NOW.plusSeconds(60));
        CodingHandlerContract.AttemptAggregateResponse aggregate =
                new CodingHandlerContract.AttemptAggregateResponse(
                        "1.0", JOB, TRACE, 1, WORKSPACE,
                        CodingHandlerContract.AttemptStatus.ACTIVE,
                        "Implement the approved change.",
                        List.of(), List.of(), List.of(), NOW, null);
        when(toolService.stageAuthority(eq("Bearer worker"), eq(JOB), eq(4)))
                .thenReturn(authority);
        when(resultService.aggregate("Bearer worker", JOB, 1)).thenReturn(aggregate);
        when(guard.reserve(eq("Bearer worker"), any())).thenAnswer(invocation -> {
            CodingModelTurnContract.Request request = invocation.getArgument(1);
            return CodingModelTurnPermit.acquired(
                    request.jobId(), request.idempotencyKey(), UUID.randomUUID());
        });

        // A correct answer that merely arrived inside a Markdown fence must survive.
        String stageResult = "{\"port\":\"feasible\",\"payload\":{\"summary\":\"ok\"}}";
        when(gateway.chat(any())).thenReturn(
                assistantText("```json\n" + stageResult + "\n```"));

        CodingHandlerContract.StageExecutionResponse response = service.execute(
                "Bearer worker", JOB, 1, RESULT,
                new CodingHandlerContract.StageExecutionRequest(
                        "1.0", TRACE, 4, 1, "coding.analyze", RESULT));

        assertThat(response.resultPort()).isEqualTo("feasible");
        assertThat(response.payload().path("summary").asText()).isEqualTo("ok");

        // Prose alone carries no object to recover, so the stage still fails.
        when(gateway.chat(any())).thenReturn(assistantText("I could not decide."));

        assertThatThrownBy(() -> service.execute(
                "Bearer worker", JOB, 1, RESULT,
                new CodingHandlerContract.StageExecutionRequest(
                        "1.0", TRACE, 4, 1, "coding.analyze", RESULT)))
                .isInstanceOf(CodingWorkerException.class);
    }

    /** A stage with no tools receives the model text verbatim, fence and all. */
    private static ProviderChatResponse assistantText(String content) {
        return new ProviderChatResponse(
                ModelProvider.GOOGLE_GENAI,
                "coding-test-model",
                content,
                12, 6, Duration.ofMillis(10));
    }

    @Test
    void runsModelToApprovedToolToTerminalStageResult() {
        ObjectMapper mapper = new ObjectMapper();
        CodingHandlerResultService resultService = mock(CodingHandlerResultService.class);
        CodingToolService toolService = mock(CodingToolService.class);
        CodingModelTurnGuard guard = mock(CodingModelTurnGuard.class);
        ProviderChatGatewayPort gateway = mock(ProviderChatGatewayPort.class);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        ProviderModelRegistration registration = new ProviderModelRegistration(
                ModelProvider.GOOGLE_GENAI,
                "coding-test-model",
                Set.of(ModelCapability.CHAT, ModelCapability.TOOL_CALLING),
                Duration.ofSeconds(30),
                2);
        CodingModelTurnService modelService = new CodingModelTurnService(
                new ProviderCapabilityRegistry(
                        ProviderLane.PRODUCT,
                        ProviderCapabilityPolicy.stage2Baseline(),
                        List.of(registration)),
                gateway,
                mapper,
                clock,
                false);
        CodingHandlerStageService service = new CodingHandlerStageService(
                resultService, toolService, guard, modelService, mapper, clock);
        CodingToolService.StageAuthority authority = new CodingToolService.StageAuthority(
                TRACE,
                4,
                UUID.fromString("11111111-1111-4111-8111-111111111111"),
                UUID.fromString("22222222-2222-4222-8222-222222222222"),
                UUID.fromString("33333333-3333-4333-8333-333333333333"),
                UUID.fromString("44444444-4444-4444-8444-444444444444"),
                "coding",
                BASE_SHA,
                "sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",
                "sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
                "coding-v1",
                Set.of("CHAT", "TOOL_CALLING"),
                Set.of("coding"),
                Set.copyOf(CodingToolService.CODING_TOOL_SCHEMA_DIGESTS.keySet()),
                NOW.plusSeconds(60));
        CodingHandlerContract.AttemptAggregateResponse aggregate =
                new CodingHandlerContract.AttemptAggregateResponse(
                        "1.0", JOB, TRACE, 1, WORKSPACE,
                        CodingHandlerContract.AttemptStatus.ACTIVE,
                        "Implement the approved change.",
                        List.of(), List.of(), List.of(), NOW, null);
        when(toolService.stageAuthority("Bearer worker", JOB, 4)).thenReturn(authority);
        when(resultService.aggregate("Bearer worker", JOB, 1)).thenReturn(aggregate);
        when(guard.reserve(eq("Bearer worker"), any())).thenAnswer(invocation -> {
            CodingModelTurnContract.Request request = invocation.getArgument(1);
            return CodingModelTurnPermit.acquired(
                    request.jobId(), request.idempotencyKey(), UUID.randomUUID());
        });
        when(gateway.chat(any())).thenReturn(
                new ProviderChatResponse(
                        ModelProvider.GOOGLE_GENAI,
                        "coding-test-model",
                        "{\"assistant\":\"\",\"toolCalls\":[{\"name\":\"read_diff\","
                                + "\"arguments\":{}}]}",
                        10, 5, Duration.ofMillis(10)),
                new ProviderChatResponse(
                        ModelProvider.GOOGLE_GENAI,
                        "coding-test-model",
                        "{\"assistant\":\"{\\\"port\\\":\\\"completed\\\","
                                + "\\\"payload\\\":{\\\"summary\\\":\\\"done\\\"}}\","
                                + "\"toolCalls\":[]}",
                        12, 6, Duration.ofMillis(10)));
        AtomicReference<UUID> submittedToolCall = new AtomicReference<>();
        when(toolService.submit(eq("Bearer worker"), any())).thenAnswer(invocation -> {
            JsonNode request = invocation.getArgument(1);
            submittedToolCall.set(UUID.fromString(request.path("toolCallId").asText()));
            return new CodingToolContract.Accepted(
                    "1.0", "TOOL_ACCEPTED",
                    UUID.fromString(request.path("requestId").asText()),
                    UUID.fromString(request.path("toolCallId").asText()),
                    JOB, TRACE, request.path("idempotencyKey").asText(), EXECUTION,
                    "ACCEPTED", "/internal/coding/tool-executions/" + EXECUTION,
                    100, NOW);
        });
        when(toolService.result("Bearer worker", EXECUTION)).thenAnswer(ignored ->
                new CodingToolContract.ResultContent(
                        "1.0",
                        UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
                        submittedToolCall.get(),
                        JOB, TRACE, "stage-tool.result", EXECUTION,
                        "application/json", 120,
                        "sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
                        "{\"workspaceId\":\"" + WORKSPACE + "\","
                                + "\"baseSha\":\"" + BASE_SHA + "\","
                                + "\"candidateSha\":\"" + BASE_SHA + "\","
                                + "\"digest\":\"" + DIFF_DIGEST + "\","
                                + "\"changedPaths\":[\"src/App.java\"]}"));
        CodingHandlerContract.StageExecutionRequest request =
                new CodingHandlerContract.StageExecutionRequest(
                        "1.0", TRACE, 4, 1, "coding.code", RESULT);

        CodingHandlerContract.StageExecutionResponse response = service.execute(
                "Bearer worker", JOB, 1, RESULT, request);

        assertThat(response.resultId()).isEqualTo(RESULT);
        assertThat(response.handlerKey()).isEqualTo("coding.code");
        assertThat(response.resultPort()).isEqualTo("completed");
        assertThat(response.workspaceId()).isEqualTo(WORKSPACE);
        assertThat(response.candidateSha()).isEqualTo(BASE_SHA);
        assertThat(response.diffDigest()).isEqualTo(DIFF_DIGEST);
        assertThat(response.payload().path("summary").asText()).isEqualTo("done");

        CodingHandlerContract.HandlerResultResponse stored =
                new CodingHandlerContract.HandlerResultResponse(
                        "1.0", RESULT, JOB, TRACE, 1, "coding.code",
                        CodingHandlerContract.ResultType.CANDIDATE, "completed",
                        WORKSPACE, BASE_SHA, DIFF_DIGEST, null,
                        response.payload(), NOW);
        when(resultService.aggregate("Bearer worker", JOB, 1)).thenReturn(
                new CodingHandlerContract.AttemptAggregateResponse(
                        "1.0", JOB, TRACE, 1, WORKSPACE,
                        CodingHandlerContract.AttemptStatus.ACTIVE,
                        "Implement the approved change.",
                        List.of(stored), List.of(), List.of(), NOW, null));
        CodingHandlerContract.StageExecutionResponse replay = service.execute(
                "Bearer worker", JOB, 1, RESULT,
                new CodingHandlerContract.StageExecutionRequest(
                        "1.0", TRACE, 4, 2, "coding.code", RESULT));
        assertThat(replay).isEqualTo(response);

        ArgumentCaptor<JsonNode> toolRequest = ArgumentCaptor.forClass(JsonNode.class);
        verify(toolService).submit(eq("Bearer worker"), toolRequest.capture());
        assertThat(toolRequest.getValue().path("tool").path("name").asText())
                .isEqualTo("read_diff");
        assertThat(toolRequest.getValue().path("repository").path("candidateSha").asText())
                .isEqualTo(BASE_SHA);

        ArgumentCaptor<ProviderChatRequest> modelRequests =
                ArgumentCaptor.forClass(ProviderChatRequest.class);
        verify(gateway, times(2)).chat(modelRequests.capture());
        assertThat(modelRequests.getAllValues().get(1).prompt())
                .contains("[tool]")
                .contains(DIFF_DIGEST);
        verify(guard, times(2)).complete(any(), any());
    }
}

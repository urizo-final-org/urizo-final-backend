package org.urizo.axmodulestudio.backend.coding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;
import org.urizo.axmodulestudio.backend.coding.dto.CodingHandlerContract;
import org.urizo.axmodulestudio.backend.coding.dto.CodingModelTurnContract;
import org.urizo.axmodulestudio.backend.coding.dto.CodingModelTurnPermit;
import org.urizo.axmodulestudio.backend.coding.dto.CodingToolContract;
import org.urizo.axmodulestudio.backend.coding.dto.GuardrailRuleContract;
import org.urizo.axmodulestudio.backend.coding.repository.CodingModelTurnGuard;
import org.urizo.axmodulestudio.backend.coding.integration.DeploymentAdapter;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ModelCapability;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ModelGatewayErrorCode;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ModelProvider;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ModelUseCase;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderCapabilityPolicy;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderCapabilityRegistry;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderChatGatewayPort;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderChatMessage;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderChatRequest;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderChatResponse;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderGatewayException;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderLane;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderModelRegistration;
import org.urizo.axmodulestudio.backend.orchestration.service.ProfileModelBindingService;
import org.urizo.axmodulestudio.backend.orchestration.service.ProfileToolBindingPolicy;

class CodingHandlerStageServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-11T08:00:00Z");
    private static final UUID JOB = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final UUID TRACE = UUID.fromString("66666666-6666-4666-8666-666666666666");
    private static final UUID RESULT = UUID.fromString("77777777-7777-4777-8777-777777777777");
    private static final UUID PROFILE = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID WORKSPACE = UUID.fromString("88888888-8888-4888-8888-888888888888");
    private static final UUID EXECUTION = UUID.fromString("99999999-9999-4999-8999-999999999999");
    private static final String BASE_SHA = "sha1:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String DIFF_DIGEST =
            "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";

    /** Shared wiring for the model-tool loop tests; the gateway and submit are per-test. */
    private record StageFixture(
            CodingHandlerStageService service,
            ProviderChatGatewayPort gateway,
            CodingToolService toolService,
            AtomicReference<UUID> submittedToolCall,
            CodingHandlerContract.StageExecutionRequest request,
            GuardrailPathSelectionService selections) { }

    private static StageFixture stageFixture(ObjectMapper mapper) {
        return stageFixture(mapper, bindingPolicy(mapper));
    }

    private static StageFixture stageFixture(
            ObjectMapper mapper, ProfileToolBindingPolicy toolBindings) {
        return stageFixture(mapper, toolBindings, 3);
    }

    /** {@code toolHistoryKeep} is the fold depth of the code stage; 3 is the production default. */
    private static StageFixture stageFixture(
            ObjectMapper mapper, ProfileToolBindingPolicy toolBindings, int toolHistoryKeep) {
        return stageFixture(mapper, toolBindings, toolHistoryKeep, ModelProvider.GOOGLE_GENAI);
    }

    /** The provider is the code node's primary binding; the fold guard reads it. */
    private static StageFixture stageFixture(
            ObjectMapper mapper, ProfileToolBindingPolicy toolBindings, int toolHistoryKeep,
            ModelProvider provider) {
        return stageFixture(mapper, toolBindings, toolHistoryKeep, provider, List.of());
    }

    /** {@code results} are the attempt's earlier stage results, such as the analysis. */
    private static StageFixture stageFixture(
            ObjectMapper mapper, ProfileToolBindingPolicy toolBindings, int toolHistoryKeep,
            ModelProvider provider, List<CodingHandlerContract.HandlerResultResponse> results) {
        return stageFixture(mapper, toolBindings, toolHistoryKeep, provider, results,
                "Implement the approved change.");
    }

    /** {@code requestText} is the Job's request as the attempt carries it. */
    private static StageFixture stageFixture(
            ObjectMapper mapper, ProfileToolBindingPolicy toolBindings, int toolHistoryKeep,
            ModelProvider provider, List<CodingHandlerContract.HandlerResultResponse> results,
            String requestText) {
        return stageFixture(mapper, toolBindings, toolHistoryKeep, provider, results,
                requestText, CodingHandlerStageService.DEFAULT_READ_ONLY_ANSWER_LIMIT);
    }

    /** {@code readOnlyAnswerLimit} is the code stage's brake on reading without an edit. */
    private static StageFixture stageFixture(
            ObjectMapper mapper, ProfileToolBindingPolicy toolBindings, int toolHistoryKeep,
            ModelProvider provider, List<CodingHandlerContract.HandlerResultResponse> results,
            String requestText, int readOnlyAnswerLimit) {
        CodingHandlerResultService resultService = mock(CodingHandlerResultService.class);
        CodingToolService toolService = mock(CodingToolService.class);
        CodingModelTurnGuard guard = mock(CodingModelTurnGuard.class);
        ProviderChatGatewayPort gateway = mock(ProviderChatGatewayPort.class);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        ProviderModelRegistration registration = new ProviderModelRegistration(
                provider,
                "coding-test-model",
                Set.of(ModelCapability.CHAT, ModelCapability.TOOL_CALLING),
                Duration.ofSeconds(30),
                2);
        CodingModelTurnService modelService = new CodingModelTurnService(
                new ProviderCapabilityRegistry(
                        ProviderLane.PRODUCT,
                        ProviderCapabilityPolicy.stage2Baseline(),
                        List.of(registration)),
                gateway, mapper, clock, false);
        ProfileModelBindingService anyBindings = mock(ProfileModelBindingService.class);
        // The profile always resolves to a binding in production: resolve either returns
        // a list or throws, so the stage never hands the turn service a null selection.
        when(anyBindings.resolve(any(), any(), any(), any())).thenReturn(List.of(registration));
        GuardrailPathSelectionService selections = mock(GuardrailPathSelectionService.class);
        CodingHandlerStageService service = new CodingHandlerStageService(
                resultService, toolService, guard, modelService,
                mock(CodingRunnerService.class), mock(DeploymentAdapter.class), anyBindings,
                selections,
                mock(GuardrailRuleService.class), mapper, clock,
                120, Duration.ofMillis(500), toolHistoryKeep, readOnlyAnswerLimit);
        CodingToolService.StageAuthority authority = new CodingToolService.StageAuthority(
                TRACE, 4,
                UUID.fromString("11111111-1111-4111-8111-111111111111"),
                UUID.fromString("22222222-2222-4222-8222-222222222222"),
                UUID.fromString("33333333-3333-4333-8333-333333333333"),
                UUID.fromString("44444444-4444-4444-8444-444444444444"),
                "coding", BASE_SHA,
                "sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",
                "sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
                "coding-v1",
                Set.of("CHAT", "TOOL_CALLING"),
                Set.of("coding"),
                Set.copyOf(CodingToolService.CODING_TOOL_SCHEMA_DIGESTS.keySet()),
                toolBindings,
                NOW.plusSeconds(60),
                PROFILE);
        CodingHandlerContract.AttemptAggregateResponse aggregate =
                new CodingHandlerContract.AttemptAggregateResponse(
                        "1.0", JOB, TRACE, 1, WORKSPACE,
                        CodingHandlerContract.AttemptStatus.ACTIVE,
                        requestText,
                        results, List.of(), List.of(), NOW, null);
        when(toolService.stageAuthority("Bearer worker", JOB, 4)).thenReturn(authority);
        when(resultService.aggregate("Bearer worker", JOB, 1)).thenReturn(aggregate);
        when(guard.reserve(eq("Bearer worker"), any())).thenAnswer(invocation -> {
            CodingModelTurnContract.Request turnRequest = invocation.getArgument(1);
            return CodingModelTurnPermit.acquired(
                    turnRequest.jobId(), turnRequest.idempotencyKey(), UUID.randomUUID());
        });
        AtomicReference<UUID> submittedToolCall = new AtomicReference<>();
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
        return new StageFixture(
                service, gateway, toolService, submittedToolCall,
                new CodingHandlerContract.StageExecutionRequest(
                        "1.0", TRACE, 4, 1, "coding.code", RESULT),
                selections);
    }

    private static org.mockito.stubbing.Answer<CodingToolContract.Accepted> acceptedSubmit(
            AtomicReference<UUID> submittedToolCall) {
        return invocation -> {
            JsonNode request = invocation.getArgument(1);
            submittedToolCall.set(UUID.fromString(request.path("toolCallId").asText()));
            return new CodingToolContract.Accepted(
                    "1.0", "TOOL_ACCEPTED",
                    UUID.fromString(request.path("requestId").asText()),
                    UUID.fromString(request.path("toolCallId").asText()),
                    JOB, TRACE, request.path("idempotencyKey").asText(), EXECUTION,
                    "ACCEPTED", "/internal/coding/tool-executions/" + EXECUTION,
                    100, NOW);
        };
    }

    /** The provider returns the tool call natively; the content stays empty. */
    private static ProviderChatResponse toolCallReply(
            String tool, String callId, String arguments) {
        return toolCallReply(ModelProvider.GOOGLE_GENAI, tool, callId, arguments);
    }

    private static ProviderChatResponse toolCallReply(
            ModelProvider provider, String tool, String callId, String arguments) {
        return new ProviderChatResponse(
                provider, "coding-test-model",
                "",
                List.of(new ProviderChatMessage.ToolCall(callId, tool, arguments)),
                10, 5, Duration.ofMillis(10));
    }

    private static ProviderChatResponse terminalReply() {
        return terminalReply(ModelProvider.GOOGLE_GENAI);
    }

    private static ProviderChatResponse terminalReply(ModelProvider provider) {
        return new ProviderChatResponse(
                provider, "coding-test-model",
                "{\"port\":\"completed\",\"payload\":{\"summary\":\"done\"}}",
                12, 6, Duration.ofMillis(10));
    }

    @ParameterizedTest
    @CsvSource({"code,completed,true,false", "review,passed,true,false", "code,completed,false,false",
            "review,passed,false,false", "code,completed,true,true", "code,completed,false,true"})
    void groupsSearchOnlyForModelInputAndReusesItWithoutChangingStoredResult(
            String node, String port, boolean groupingEnabled, boolean batched) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String handler = "coding." + node;
        var code = new CodingHandlerContract.HandlerResultResponse(
                "1.0", UUID.randomUUID(), JOB, TRACE, 1, "coding.code",
                CodingHandlerContract.ResultType.CANDIDATE, "completed", WORKSPACE, BASE_SHA,
                DIFF_DIGEST, null, mapper.createObjectNode(), NOW);
        StageFixture fixture = stageFixture(mapper, bindingPolicy(mapper), 0, ModelProvider.OPENAI,
                node.equals("review") ? List.of(code) : List.of());
        org.springframework.test.util.ReflectionTestUtils.setField(
                fixture.service(), "searchResultGroupingEnabled", groupingEnabled);
        String rawSearch = SearchCodeModelViewTest.fixture().toString();
        UUID searchCall = UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd");
        CodingToolContract.ResultContent diffTemplate = fixture.toolService().result("Bearer worker", EXECUTION);
        AtomicReference<CodingToolContract.ResultContent> storedSearch = new AtomicReference<>();
        when(fixture.toolService().result("Bearer worker", EXECUTION)).thenAnswer(ignored -> {
            String raw = searchCall.equals(fixture.submittedToolCall().get()) ? rawSearch : diffTemplate.content();
            var result = new CodingToolContract.ResultContent("1.0", UUID.randomUUID(),
                    fixture.submittedToolCall().get(), JOB, TRACE, "stage-tool.result", EXECUTION,
                    "application/json", raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                    diffTemplate.digest(), raw);
            if (searchCall.equals(result.toolCallId())) storedSearch.set(result);
            return result;
        });
        when(fixture.toolService().submitForNode(eq("Bearer worker"), any(), eq(node)))
                .thenAnswer(acceptedSubmit(fixture.submittedToolCall()));
        ProviderChatResponse terminal = node.equals("code") ? terminalReply(ModelProvider.OPENAI)
                : new ProviderChatResponse(ModelProvider.OPENAI, "coding-test-model",
                        "{\"port\":\"passed\",\"payload\":{\"reportSummary\":\"done\",\"criteriaResults\":[]}}",
                        12, 6, Duration.ofMillis(10));
        ProviderChatResponse searchReply = batched
                ? new ProviderChatResponse(ModelProvider.OPENAI, "coding-test-model", "", List.of(
                        new ProviderChatMessage.ToolCall(READ_B, "read_file", "{\"path\":\"src/App.java\"}"),
                        new ProviderChatMessage.ToolCall(searchCall.toString(), "search_code", "{\"query\":\"App\",\"scope\":\"src\"}")),
                        10, 5, Duration.ofMillis(10))
                : toolCallReply(ModelProvider.OPENAI, "search_code", searchCall.toString(), "{\"query\":\"App\",\"scope\":\"src\"}");
        when(fixture.gateway().chat(any())).thenReturn(
                searchReply,
                toolCallReply(ModelProvider.OPENAI, "read_diff", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "{}"),
                terminal);

        var request = new CodingHandlerContract.StageExecutionRequest("1.0", TRACE, 4, 1, handler, RESULT);
        var result = fixture.service().execute("Bearer worker", JOB, 1, RESULT, request);
        assertThat(result.resultPort()).isEqualTo(port);
        ArgumentCaptor<ProviderChatRequest> routed = ArgumentCaptor.forClass(ProviderChatRequest.class);
        verify(fixture.gateway(), times(3)).chat(routed.capture());
        String compact = toolBodies(routed.getAllValues().get(1)).get(batched ? 1 : 0);
        if (groupingEnabled) {
            assertThat(compact).contains("groups[path] contains rows", "across ALL paths",
                    "not group order", "including whitespace").isNotEqualTo(rawSearch);
        } else {
            assertThat(compact).isEqualTo(rawSearch);
        }
        var initialMessages = routed.getAllValues().get(0).messages();
        for (ProviderChatRequest turn : routed.getAllValues()) {
            assertThat(turn.messages().subList(0, initialMessages.size())).isEqualTo(initialMessages);
            assertThat(turn.responseFormat().structured()).isFalse();
        }
        assertThat(toolBodies(routed.getAllValues().get(2)))
                .containsExactlyElementsOf(batched ? List.of(diffTemplate.content(), compact, diffTemplate.content())
                        : List.of(compact, diffTemplate.content()));
        assertThat(storedSearch.get().content()).isEqualTo(rawSearch);
        assertThat(storedSearch.get().digest()).isEqualTo(diffTemplate.digest());
        var providerTool = routed.getAllValues().get(1).messages().stream()
                .filter(message -> message.role() == ProviderChatMessage.Role.TOOL
                        && searchCall.toString().equals(message.toolCallId())).findFirst().orElseThrow();
        assertThat(providerTool.toolCallId()).isEqualTo(searchCall.toString());
        assertThat(providerTool.toolName()).isEqualTo("search_code");
        JsonNode message = org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                fixture.service(), "toolMessage", handler, "search_code", storedSearch.get());
        assertThat(message.path("result").path("digest").asText()).isEqualTo(diffTemplate.digest());
        assertThat(message.path("result").path("sizeBytes").asInt()).isEqualTo(storedSearch.get().sizeBytes());
        assertThat(message.path("result").path("resultRef").asText()).endsWith(EXECUTION + "/result");
        org.springframework.test.util.ReflectionTestUtils.setField(fixture.service(), "searchResultGroupingEnabled", false);
        JsonNode disabled = org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                fixture.service(), "toolMessage", handler, "search_code", storedSearch.get());
        assertThat(disabled.path("content").asText()).isEqualTo(rawSearch);
    }

    private static List<String> toolBodies(ProviderChatRequest request) {
        return request.messages().stream()
                .filter(message -> message.role() == ProviderChatMessage.Role.TOOL)
                .map(ProviderChatMessage::content)
                .toList();
    }

    private static ProviderChatResponse[] threeReadsThenDone() {
        return threeReadsThenDone(ModelProvider.GOOGLE_GENAI);
    }

    private static ProviderChatResponse[] threeReadsThenDone(ModelProvider provider) {
        return new ProviderChatResponse[] {
            toolCallReply(provider, "read_file", "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
                    "{\"path\":\"src/App.java\"}"),
            toolCallReply(provider, "read_file", "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
                    "{\"path\":\"src/App.java\",\"startLine\":1,\"endLine\":9}"),
            toolCallReply(provider, "search_code", "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
                    "{\"query\":\"App\",\"scope\":\"src\"}"),
            terminalReply(provider),
        };
    }

    /**
     * Measured on Job 45593ba8: 91% of the code stage's 263,279 input tokens were earlier
     * tool results replayed on every later answer. With a fold depth of one, each new read
     * or search folds the one before it, and the request that follows carries the note
     * instead of the body. read_diff is never folded.
     */
    @ParameterizedTest
    @CsvSource({"true,2048,false", "true,2049,true", "false,2048,true"})
    void smallReadRetentionUsesAnOptInByteBoundary(boolean enabled, int size, boolean folded) {
        ObjectMapper mapper = new ObjectMapper();
        StageFixture fixture = stageFixture(mapper, bindingPolicy(mapper), 1, ModelProvider.OPENAI);
        org.springframework.test.util.ReflectionTestUtils.setField(
                fixture.service(), "smallReadHistoryRetentionEnabled", enabled);
        List<JsonNode> history = new ArrayList<>();
        // 682 Korean characters = 2046 UTF-8 bytes; a character limit would incorrectly keep 2049.
        String text = "한".repeat(682) + "x".repeat(size - 2046);
        appendRetentionResult(mapper, history, "read_file", text);
        appendRetentionResult(mapper, history, "read_file", "latest");
        JsonNode original = history.get(1).deepCopy();
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                fixture.service(), "foldOldToolResults", history, "coding.code");
        assertThat(history.get(1).path("content").asText())
                .isEqualTo(folded ? CodingHandlerStageService.FOLDED_TOOL_CONTENT : text);
        assertThat(history.get(1).path("toolCallId")).isEqualTo(original.path("toolCallId"));
        assertThat(history.get(1).path("result")).isEqualTo(original.path("result"));
        String snapshot = history.toString();
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                fixture.service(), "foldOldToolResults", history, "coding.code");
        assertThat(history.toString()).isEqualTo(snapshot);
    }

    @Test
    void smallReadRetentionCapsAdditionalOldBodiesAndDoesNotPreserveSearchResults() {
        ObjectMapper mapper = new ObjectMapper();
        StageFixture fixture = stageFixture(mapper, bindingPolicy(mapper), 1, ModelProvider.OPENAI);
        org.springframework.test.util.ReflectionTestUtils.setField(
                fixture.service(), "smallReadHistoryRetentionEnabled", true);
        List<JsonNode> history = new ArrayList<>();
        appendRetentionResult(mapper, history, "read_diff", "diff preserved");
        for (int i = 0; i < 5; i++) appendRetentionResult(mapper, history, "read_file", "x".repeat(2048));
        appendRetentionResult(mapper, history, "search_code", "small search");
        appendRetentionResult(mapper, history, "read_file", "latest");
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                fixture.service(), "foldOldToolResults", history, "coding.code");
        assertThat(history.get(1).path("content").asText()).isEqualTo("diff preserved");
        assertThat(history.get(3).path("content").asText()).isEqualTo(CodingHandlerStageService.FOLDED_TOOL_CONTENT);
        for (int i = 5; i <= 11; i += 2) assertThat(history.get(i).path("content").asText()).hasSize(2048);
        assertThat(history.get(13).path("content").asText()).isEqualTo(CodingHandlerStageService.FOLDED_TOOL_CONTENT);
        assertThat(history.get(15).path("content").asText()).isEqualTo("latest");
    }

    @ParameterizedTest
    @CsvSource({"coding.review,1", "coding.analyze,1", "coding.code,0"})
    void smallReadRetentionDoesNotEnableFoldingOutsideExistingScope(String handler, int keep) {
        ObjectMapper mapper = new ObjectMapper();
        StageFixture fixture = stageFixture(mapper, bindingPolicy(mapper), keep, ModelProvider.OPENAI);
        org.springframework.test.util.ReflectionTestUtils.setField(
                fixture.service(), "smallReadHistoryRetentionEnabled", true);
        List<JsonNode> history = new ArrayList<>();
        appendRetentionResult(mapper, history, "read_file", "x".repeat(9000));
        appendRetentionResult(mapper, history, "search_code", "latest");
        String original = history.toString();
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                fixture.service(), "foldOldToolResults", history, handler);
        assertThat(history.toString()).isEqualTo(original);
    }

    private static void appendRetentionResult(ObjectMapper mapper, List<JsonNode> history, String tool, String text) {
        String id = UUID.randomUUID().toString();
        ObjectNode assistant = mapper.createObjectNode().put("role", "assistant");
        assistant.putArray("toolCalls").addObject().put("name", tool).put("toolCallId", id);
        history.add(assistant);
        ObjectNode result = mapper.createObjectNode().put("role", "tool").put("toolCallId", id).put("content", text);
        result.putObject("result").put("digest", DIFF_DIGEST).put("resultRef", "unchanged");
        history.add(result);
    }

    @ParameterizedTest
    @CsvSource({"true,6144,false", "true,6145,true", "false,6144,true"})
    void smallSearchRetentionHasAnIndependentUtf8Boundary(boolean enabled, int size, boolean folded) {
        ObjectMapper mapper = new ObjectMapper();
        StageFixture fixture = stageFixture(mapper, bindingPolicy(mapper), 1, ModelProvider.OPENAI);
        org.springframework.test.util.ReflectionTestUtils.setField(
                fixture.service(), "smallSearchHistoryRetentionEnabled", enabled);
        List<JsonNode> history = new ArrayList<>();
        String content = "한".repeat(2048) + "x".repeat(size - 6144);
        appendRetentionResult(mapper, history, "search_code", content);
        appendRetentionResult(mapper, history, "read_file", "old read");
        appendRetentionResult(mapper, history, "read_file", "latest");
        JsonNode original = history.get(1).deepCopy();
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                fixture.service(), "foldOldToolResults", history, "coding.code");
        assertThat(history.get(1).path("content").asText())
                .isEqualTo(folded ? CodingHandlerStageService.FOLDED_TOOL_CONTENT : content);
        assertThat(history.get(1).path("toolCallId")).isEqualTo(original.path("toolCallId"));
        assertThat(history.get(1).path("result")).isEqualTo(original.path("result"));
        assertThat(history.get(3).path("content").asText()).isEqualTo(CodingHandlerStageService.FOLDED_TOOL_CONTENT);
        String snapshot = history.toString();
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                fixture.service(), "foldOldToolResults", history, "coding.code");
        assertThat(history.toString()).isEqualTo(snapshot);
    }

    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void batchedResultsUseCallIdsAndShareTheAdditionalRetentionBudget(boolean reads, boolean searches) {
        ObjectMapper mapper = new ObjectMapper();
        StageFixture fixture = stageFixture(mapper, bindingPolicy(mapper), 1, ModelProvider.OPENAI);
        org.springframework.test.util.ReflectionTestUtils.setField(fixture.service(), "smallReadHistoryRetentionEnabled", reads);
        org.springframework.test.util.ReflectionTestUtils.setField(fixture.service(), "smallSearchHistoryRetentionEnabled", searches);
        List<JsonNode> history = new ArrayList<>();
        List<String> names = List.of("read_file", "search_code", "read_file", "read_file", "search_code");
        List<String> bodies = List.of("a".repeat(2048), "b".repeat(6144), "c".repeat(2048),
                "d".repeat(9000), "e".repeat(9000));
        List<ObjectNode> results = new ArrayList<>();
        // Three older results share one assistant answer; two newer results share the next.
        for (int start : List.of(0, 3)) {
            int end = start == 0 ? 3 : 5;
            ObjectNode assistant = mapper.createObjectNode().put("role", "assistant");
            ArrayNode calls = assistant.putArray("toolCalls");
            history.add(assistant);
            for (int i = start; i < end; i++) {
                String id = UUID.randomUUID().toString();
                calls.addObject().put("toolCallId", id).put("name", names.get(i));
                ObjectNode result = mapper.createObjectNode().put("role", "tool")
                        .put("toolCallId", id).put("content", bodies.get(i));
                result.putObject("result").put("digest", DIFF_DIGEST);
                history.add(result);
                results.add(result);
            }
        }
        List<JsonNode> original = results.stream().map(value -> (JsonNode) value.deepCopy()).toList();
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(fixture.service(), "foldOldToolResults", history, "coding.code");
        assertThat(results.get(0).path("content").asText())
                .isEqualTo(reads && !searches ? bodies.get(0) : CodingHandlerStageService.FOLDED_TOOL_CONTENT);
        assertThat(results.get(1).path("content").asText())
                .isEqualTo(searches ? bodies.get(1) : CodingHandlerStageService.FOLDED_TOOL_CONTENT);
        assertThat(results.get(2).path("content").asText())
                .isEqualTo(reads ? bodies.get(2) : CodingHandlerStageService.FOLDED_TOOL_CONTENT);
        for (int i = 3; i < 5; i++) assertThat(results.get(i).path("content").asText()).isEqualTo(bodies.get(i));
        for (int i = 0; i < results.size(); i++) {
            assertThat(results.get(i).path("toolCallId")).isEqualTo(original.get(i).path("toolCallId"));
            assertThat(results.get(i).path("result")).isEqualTo(original.get(i).path("result"));
        }
        String once = history.toString();
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(fixture.service(), "foldOldToolResults", history, "coding.code");
        assertThat(history.toString()).isEqualTo(once);
    }

    @Test
    void searchAndReadShareTheExistingBudgetWithoutRestoringFoldedResults() {
        ObjectMapper mapper = new ObjectMapper();
        StageFixture fixture = stageFixture(mapper, bindingPolicy(mapper), 1, ModelProvider.OPENAI);
        org.springframework.test.util.ReflectionTestUtils.setField(fixture.service(), "smallReadHistoryRetentionEnabled", true);
        org.springframework.test.util.ReflectionTestUtils.setField(fixture.service(), "smallSearchHistoryRetentionEnabled", true);
        List<JsonNode> history = new ArrayList<>();
        appendRetentionResult(mapper, history, "read_diff", "diff preserved");
        appendRetentionResult(mapper, history, "search_code", CodingHandlerStageService.FOLDED_TOOL_CONTENT);
        appendRetentionResult(mapper, history, "read_file", "old read");
        appendRetentionResult(mapper, history, "read_file", "x".repeat(2048));
        appendRetentionResult(mapper, history, "search_code", "x".repeat(6144));
        appendRetentionResult(mapper, history, "read_file", "latest");
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(fixture.service(), "foldOldToolResults", history, "coding.code");
        assertThat(history.get(1).path("content").asText()).isEqualTo("diff preserved");
        assertThat(history.get(3).path("content").asText()).isEqualTo(CodingHandlerStageService.FOLDED_TOOL_CONTENT);
        assertThat(history.get(5).path("content").asText()).isEqualTo(CodingHandlerStageService.FOLDED_TOOL_CONTENT);
        assertThat(history.get(7).path("content").asText()).hasSize(2048);
        assertThat(history.get(9).path("content").asText()).hasSize(6144);
        assertThat(history.get(11).path("content").asText()).isEqualTo("latest");
    }

    @ParameterizedTest
    @CsvSource({"coding.review,1", "coding.analyze,1", "coding.code,0"})
    void searchRetentionDoesNotChangeOtherHandlersOrDisabledFolding(String handler, int keep) {
        ObjectMapper mapper = new ObjectMapper();
        StageFixture fixture = stageFixture(mapper, bindingPolicy(mapper), keep, ModelProvider.OPENAI);
        org.springframework.test.util.ReflectionTestUtils.setField(fixture.service(), "smallReadHistoryRetentionEnabled", true);
        org.springframework.test.util.ReflectionTestUtils.setField(fixture.service(), "smallSearchHistoryRetentionEnabled", true);
        List<JsonNode> history = new ArrayList<>();
        appendRetentionResult(mapper, history, "search_code", "x".repeat(9000));
        appendRetentionResult(mapper, history, "read_file", "latest");
        String original = history.toString();
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(fixture.service(), "foldOldToolResults", history, handler);
        assertThat(history.toString()).isEqualTo(original);
    }

    @ParameterizedTest
    @CsvSource({"true,100", "true,7000", "false,100"})
    void searchBudgetAllowsCodeResultToReachReview(boolean enabled, int searchSize) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        StageFixture code = stageFixture(mapper, bindingPolicy(mapper), 1, ModelProvider.OPENAI);
        org.springframework.test.util.ReflectionTestUtils.setField(code.service(), "smallReadHistoryRetentionEnabled", enabled);
        org.springframework.test.util.ReflectionTestUtils.setField(code.service(), "smallSearchHistoryRetentionEnabled", enabled);
        UUID searchId = UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd");
        var diff = code.toolService().result("Bearer worker", EXECUTION);
        when(code.toolService().submitForNode(eq("Bearer worker"), any(), eq("code")))
                .thenAnswer(acceptedSubmit(code.submittedToolCall()));
        when(code.toolService().result("Bearer worker", EXECUTION)).thenAnswer(ignored -> {
            boolean search = searchId.equals(code.submittedToolCall().get());
            return new CodingToolContract.ResultContent("1.0", RESULT, code.submittedToolCall().get(), JOB, TRACE,
                    "stage-tool.result", EXECUTION, search ? "text/plain" : "application/json", searchSize,
                    diff.digest(), search ? "x".repeat(searchSize) : diff.content());
        });
        when(code.gateway().chat(any())).thenReturn(
                toolCallReply(ModelProvider.OPENAI, "read_diff", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "{}"),
                toolCallReply(ModelProvider.OPENAI, "search_code", searchId.toString(), "{\"query\":\"App\"}"),
                toolCallReply(ModelProvider.OPENAI, "read_file", "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", "{\"path\":\"src/App.java\"}"),
                terminalReply(ModelProvider.OPENAI));
        var completed = code.service().execute("Bearer worker", JOB, 1, RESULT, code.request());
        assertThat(completed.resultPort()).isEqualTo("completed");
        ArgumentCaptor<ProviderChatRequest> codeRequests = ArgumentCaptor.forClass(ProviderChatRequest.class);
        verify(code.gateway(), times(4)).chat(codeRequests.capture());
        assertThat(toolBodies(codeRequests.getAllValues().get(3)).get(1))
                .contains(enabled && searchSize <= 6144 ? "x".repeat(searchSize) : CodingHandlerStageService.FOLDED_TOOL_CONTENT);
        // Explicitly pass the actual code stage result through the aggregate boundary;
        // this checks Backend handoff, not the external Snapshot Runner or persistence.
        var stored = new CodingHandlerContract.HandlerResultResponse("1.0", completed.resultId(), JOB, TRACE, 1,
                completed.handlerKey(), CodingHandlerContract.ResultType.CANDIDATE, completed.resultPort(),
                completed.workspaceId(), completed.candidateSha(), completed.diffDigest(), completed.validationHash(), completed.payload(), NOW);
        StageFixture review = stageFixture(mapper, bindingPolicy(mapper), 1, ModelProvider.OPENAI, List.of(stored));
        org.springframework.test.util.ReflectionTestUtils.setField(review.service(), "smallReadHistoryRetentionEnabled", enabled);
        org.springframework.test.util.ReflectionTestUtils.setField(review.service(), "smallSearchHistoryRetentionEnabled", enabled);
        when(review.toolService().submitForNode(eq("Bearer worker"), any(), eq("review")))
                .thenAnswer(acceptedSubmit(review.submittedToolCall()));
        when(review.gateway().chat(any())).thenReturn(
                toolCallReply(ModelProvider.OPENAI, "read_diff", "cccccccc-cccc-4ccc-8ccc-cccccccccccc", "{}"),
                new ProviderChatResponse(ModelProvider.OPENAI, "coding-test-model",
                        "{\"port\":\"passed\",\"payload\":{\"reportSummary\":\"reviewed\",\"criteriaResults\":[]}}", 12, 6, Duration.ZERO));
        UUID reviewResultId = UUID.randomUUID();
        var reviewed = review.service().execute("Bearer worker", JOB, 1, reviewResultId,
                new CodingHandlerContract.StageExecutionRequest("1.0", TRACE, 4, 1, "coding.review", reviewResultId));
        assertThat(reviewed.resultPort()).isEqualTo("passed");
        assertThat(reviewed.candidateSha()).isEqualTo(completed.candidateSha());
        assertThat(reviewed.diffDigest()).isEqualTo(completed.diffDigest());
        ArgumentCaptor<ProviderChatRequest> reviewRequests = ArgumentCaptor.forClass(ProviderChatRequest.class);
        verify(review.gateway(), times(2)).chat(reviewRequests.capture());
        JsonNode prior = mapper.readTree(reviewRequests.getAllValues().get(0).messages().get(1).content()).path("priorResults").get(0);
        assertThat(prior.path("candidateSha").asText()).isEqualTo(completed.candidateSha());
        assertThat(prior.path("diffDigest").asText()).isEqualTo(completed.diffDigest());
        assertThat(prior.path("payload")).isEqualTo(completed.payload());
    }

    @ParameterizedTest
    @CsvSource({"OPENAI", "ANTHROPIC", "GOOGLE_GENAI"})
    void smallReadRetentionPreservesEarlierBodiesAcrossTheModelToolLoop(ModelProvider provider) {
        ObjectMapper mapper = new ObjectMapper();
        StageFixture fixture = stageFixture(mapper, bindingPolicy(mapper), 1, provider);
        org.springframework.test.util.ReflectionTestUtils.setField(
                fixture.service(), "smallReadHistoryRetentionEnabled", true);
        when(fixture.toolService().submitForNode(eq("Bearer worker"), any(), eq("code")))
                .thenAnswer(acceptedSubmit(fixture.submittedToolCall()));
        when(fixture.gateway().chat(any())).thenReturn(
                toolCallReply(provider, "read_diff", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "{}"),
                threeReadsThenDone(provider));
        assertThat(fixture.service().execute("Bearer worker", JOB, 1, RESULT, fixture.request()).resultPort())
                .isEqualTo("completed");
        ArgumentCaptor<ProviderChatRequest> routed = ArgumentCaptor.forClass(ProviderChatRequest.class);
        verify(fixture.gateway(), times(5)).chat(routed.capture());
        List<String> fourth = toolBodies(routed.getAllValues().get(3));
        List<String> fifth = toolBodies(routed.getAllValues().get(4));
        assertThat(fifth).hasSize(4).allSatisfy(body -> assertThat(body).contains(DIFF_DIGEST));
        assertThat(fifth.subList(0, 3)).isEqualTo(fourth);
        assertThat(routed.getAllValues().get(0).messages().get(0).content())
                .contains("small read_file results may remain", "copied from a fresh read, never from memory");
    }

    @Test
    void foldsReadResultsOlderThanTheKeptOnesInTheCodeStage() {
        ObjectMapper mapper = new ObjectMapper();
        StageFixture fixture = stageFixture(
                mapper, bindingPolicy(mapper), 1, ModelProvider.ANTHROPIC);
        when(fixture.toolService().submitForNode(eq("Bearer worker"), any(), eq("code")))
                .thenAnswer(acceptedSubmit(fixture.submittedToolCall()));
        when(fixture.gateway().chat(any())).thenReturn(
                toolCallReply(ModelProvider.ANTHROPIC,
                        "read_diff", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "{}"),
                threeReadsThenDone(ModelProvider.ANTHROPIC));

        CodingHandlerContract.StageExecutionResponse response = fixture.service().execute(
                "Bearer worker", JOB, 1, RESULT, fixture.request());

        assertThat(response.resultPort()).isEqualTo("completed");
        ArgumentCaptor<ProviderChatRequest> routed =
                ArgumentCaptor.forClass(ProviderChatRequest.class);
        verify(fixture.gateway(), times(5)).chat(routed.capture());
        // Fourth request: read_diff, read_file, read_file so far - only the first read folds.
        List<String> fourth = toolBodies(routed.getAllValues().get(3));
        assertThat(fourth).hasSize(3);
        assertThat(fourth.get(0)).contains(DIFF_DIGEST);
        assertThat(fourth.get(1)).contains("folded").doesNotContain(DIFF_DIGEST);
        assertThat(fourth.get(2)).contains(DIFF_DIGEST);
        // Fifth request: the search arrived, so the second read folds too. The first read's
        // note is the same text as before - folded once, not rewritten.
        List<String> fifth = toolBodies(routed.getAllValues().get(4));
        assertThat(fifth).hasSize(4);
        assertThat(fifth.get(0)).contains(DIFF_DIGEST);
        assertThat(fifth.get(1)).isEqualTo(fourth.get(1));
        assertThat(fifth.get(2)).isEqualTo(fourth.get(1));
        assertThat(fifth.get(3)).contains(DIFF_DIGEST);
        assertThat(routed.getAllValues().get(0).messages().get(0).content())
                .contains("Results from your last 1 reading answers", "up to 6 read_file/search_code bodies");
    }

    /**
     * Job 45593ba8's coding stage opened a 558-line screen file whole, was refused, and read
     * lines 1-130 and 130-300 to land two edits at 162 and 230 - text that rode along on
     * fifteen later answers. The server now reads each target file first and hands the model
     * only the line count and the declarations. A larger file's refusal already carries both;
     * a file the workspace will not return at all is left out instead of failing the stage;
     * and only the first three target files are read, because every outline is re-sent.
     */
    @Test
    void theCodeStageStartsFromAnOutlineOfEachTargetFileInsteadOfItsText() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        StageFixture fixture = stageFixture(
                mapper, bindingPolicy(mapper), 3, ModelProvider.GOOGLE_GENAI,
                List.of(analysisNaming(mapper,
                        "src/Small.java", "src/Large.tsx", "src/Huge.tsx", "src/Fourth.java")));
        List<JsonNode> submitted = new ArrayList<>();
        AtomicReference<String> lastPath = new AtomicReference<>("");
        String largeRefusal = "The file is 29470 characters over 559 lines - too large to read "
                + "whole. Outline (line: declaration):\n173: export function PortalHome() {";
        when(fixture.toolService().submitForNode(eq("Bearer worker"), any(), eq("code")))
                .thenAnswer(invocation -> {
                    JsonNode toolRequest = invocation.getArgument(1);
                    submitted.add(toolRequest);
                    String path = toolRequest.path("tool").path("arguments").path("path").asText("");
                    lastPath.set(path);
                    if ("src/Large.tsx".equals(path)) {
                        throw new CodingToolException(
                                "TOOL_ARGUMENTS_INVALID", largeRefusal, HttpStatus.BAD_REQUEST);
                    }
                    if ("src/Huge.tsx".equals(path)) {
                        throw new CodingToolException("TOOL_EXECUTION_FAILED",
                                "The MCP coding tool refused the call. RESULT_TOO_LARGE: "
                                        + "The requested file is too large.",
                                HttpStatus.BAD_GATEWAY);
                    }
                    return acceptedSubmit(fixture.submittedToolCall()).answer(invocation);
                });
        doAnswer(ignored -> "src/Small.java".equals(lastPath.get())
                ? new CodingToolContract.ResultContent(
                        "1.0", UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
                        fixture.submittedToolCall().get(),
                        JOB, TRACE, "stage-tool.result", EXECUTION, "text/plain", 80,
                        "sha256:" + "f".repeat(64),
                        "package demo;\n\npublic class Small {\n"
                                + "    public void updateUser() {\n    }\n}\n")
                : new CodingToolContract.ResultContent(
                        "1.0", UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
                        fixture.submittedToolCall().get(),
                        JOB, TRACE, "stage-tool.result", EXECUTION, "application/json", 120,
                        "sha256:" + "f".repeat(64),
                        "{\"workspaceId\":\"" + WORKSPACE + "\","
                                + "\"baseSha\":\"" + BASE_SHA + "\","
                                + "\"candidateSha\":\"" + BASE_SHA + "\","
                                + "\"digest\":\"" + DIFF_DIGEST + "\","
                                + "\"changedPaths\":[\"src/App.java\"]}"))
                .when(fixture.toolService()).result("Bearer worker", EXECUTION);
        when(fixture.gateway().chat(any())).thenReturn(
                toolCallReply("read_diff", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "{}"),
                terminalReply());

        CodingHandlerContract.StageExecutionResponse response = fixture.service().execute(
                "Bearer worker", JOB, 1, RESULT, fixture.request());

        assertThat(response.resultPort()).isEqualTo("completed");
        List<JsonNode> reads = submitted.stream()
                .filter(toolRequest -> "read_file".equals(
                        toolRequest.path("tool").path("name").asText()))
                .toList();
        assertThat(reads)
                .extracting(toolRequest ->
                        toolRequest.path("tool").path("arguments").path("path").asText())
                .containsExactly("src/Small.java", "src/Large.tsx", "src/Huge.tsx");
        assertThat(reads)
                .extracting(toolRequest -> toolRequest.path("toolCallId").asText())
                .doesNotHaveDuplicates();
        ArgumentCaptor<ProviderChatRequest> routed =
                ArgumentCaptor.forClass(ProviderChatRequest.class);
        verify(fixture.gateway(), times(2)).chat(routed.capture());
        String context = firstUserMessage(routed.getAllValues().get(0));
        JsonNode outlines = mapper.readTree(context).path("targetFileOutlines");
        assertThat(outlines).hasSize(2);
        assertThat(outlines.get(0).path("path").asText()).isEqualTo("src/Small.java");
        assertThat(outlines.get(0).path("outline").asText()).isEqualTo(
                "7 lines. Outline (line: declaration):\n"
                        + "3: public class Small {\n"
                        + "4:     public void updateUser() {");
        assertThat(outlines.get(1).path("path").asText()).isEqualTo("src/Large.tsx");
        assertThat(outlines.get(1).path("outline").asText()).isEqualTo(largeRefusal);
        assertThat(context).doesNotContain("package demo");
        assertThat(routed.getAllValues().get(0).prompt()).contains("targetFileOutlines");
    }

    @Test
    void withoutTargetFilesTheCodeStageReadsNothingBeforeTheModelAnswers() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        StageFixture fixture = stageFixture(mapper);
        List<JsonNode> submitted = new ArrayList<>();
        when(fixture.toolService().submitForNode(eq("Bearer worker"), any(), eq("code")))
                .thenAnswer(invocation -> {
                    submitted.add(invocation.getArgument(1));
                    return acceptedSubmit(fixture.submittedToolCall()).answer(invocation);
                });
        when(fixture.gateway().chat(any())).thenReturn(
                toolCallReply("read_diff", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "{}"),
                terminalReply());

        fixture.service().execute("Bearer worker", JOB, 1, RESULT, fixture.request());

        // The stage's own read_diff, then the model's - no read_file before the first answer.
        assertThat(submitted)
                .extracting(toolRequest -> toolRequest.path("tool").path("name").asText())
                .containsExactly("read_diff", "read_diff");
        ArgumentCaptor<ProviderChatRequest> routed =
                ArgumentCaptor.forClass(ProviderChatRequest.class);
        verify(fixture.gateway(), times(2)).chat(routed.capture());
        assertThat(mapper.readTree(firstUserMessage(routed.getAllValues().get(0)))
                .has("targetFileOutlines")).isFalse();
    }

    /*
     * AI04-034 ②: on the four measured haiku Jobs of one request, the model spent two to
     * three answers finding the screen text the request quoted, and on 543eb70f it read the
     * same PortalHome range nine times over three rounds. A phrase the request quotes is now
     * looked up in the target files before the first answer, and the declaration holding it
     * is handed over as an excerpt - from memory when the file was read whole.
     */
    @Test
    void aPhraseTheRequestQuotesIsHandedOverAsTheDeclarationHoldingIt() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        StageFixture fixture = stageFixture(
                mapper, bindingPolicy(mapper), 3, ModelProvider.GOOGLE_GENAI,
                List.of(analysisNaming(mapper, "src/Small.java")),
                "Rename 'updateUser' on the members screen.");
        List<JsonNode> submitted = new ArrayList<>();
        answerToolsByRequest(fixture, submitted, toolRequest ->
                "read_file".equals(toolRequest.path("tool").path("name").asText())
                        ? textResult(fixture, SMALL_JAVA)
                        : jsonResult(fixture, diffJson()));
        when(fixture.gateway().chat(any())).thenReturn(terminalReply());

        fixture.service().execute("Bearer worker", JOB, 1, RESULT, fixture.request());

        // The whole read that outlines the file, then the stage's read_diff - no search.
        assertThat(submitted)
                .extracting(toolRequest -> toolRequest.path("tool").path("name").asText())
                .containsExactly("read_file", "read_diff");
        ArgumentCaptor<ProviderChatRequest> routed =
                ArgumentCaptor.forClass(ProviderChatRequest.class);
        verify(fixture.gateway()).chat(routed.capture());
        JsonNode excerpts = mapper.readTree(firstUserMessage(routed.getValue()))
                .path("targetFileExcerpts");
        assertThat(excerpts).hasSize(1);
        assertThat(excerpts.get(0).path("path").asText()).isEqualTo("src/Small.java");
        assertThat(excerpts.get(0).path("startLine").asInt()).isEqualTo(4);
        assertThat(excerpts.get(0).path("endLine").asInt()).isEqualTo(7);
        assertThat(excerpts.get(0).path("content").asText())
                .isEqualTo("    public void updateUser() {\n    }\n}\n");
        assertThat(routed.getValue().messages().get(0).content())
                .contains("targetFileExcerpts, when present");
    }

    /*
     * A file refused as too large is searched by the workspace within that file only, and
     * the declaration holding the first code match is read once, ranged - two tool calls the
     * model no longer spends answers on.
     */
    @Test
    void aPhraseInALargeFileIsSearchedThereAndItsRangeReadOnce() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        StageFixture fixture = stageFixture(
                mapper, bindingPolicy(mapper), 3, ModelProvider.GOOGLE_GENAI,
                List.of(analysisNaming(mapper, "src/Large.tsx")),
                "'진행 중만 보기' 버튼을 추가해줘");
        String largeRefusal = "The file is 29470 characters over 559 lines - too large to read "
                + "whole. Call read_file with startLine and endLine. Outline (line: declaration):"
                + "\n173: export function PortalHome() {\n210: export function Other() {";
        String range = "export function PortalHome() {\n  return <button>진행 중만 보기</button>;\n}";
        List<JsonNode> submitted = new ArrayList<>();
        AtomicReference<JsonNode> last = new AtomicReference<>();
        when(fixture.toolService().submitForNode(eq("Bearer worker"), any(), eq("code")))
                .thenAnswer(invocation -> {
                    JsonNode toolRequest = invocation.getArgument(1);
                    JsonNode arguments = toolRequest.path("tool").path("arguments");
                    if ("read_file".equals(toolRequest.path("tool").path("name").asText())
                            && !arguments.has("startLine")) {
                        throw new CodingToolException(
                                "TOOL_ARGUMENTS_INVALID", largeRefusal, HttpStatus.BAD_REQUEST);
                    }
                    submitted.add(toolRequest);
                    last.set(toolRequest);
                    return acceptedSubmit(fixture.submittedToolCall()).answer(invocation);
                });
        doAnswer(ignored -> switch (last.get().path("tool").path("name").asText()) {
            case "search_code" -> jsonResult(fixture, "{\"matches\":[{\"path\":\"src/Large.tsx\","
                    + "\"line\":180,\"column\":3,\"preview\":\"  <button>진행 중만 보기</button>\"}],"
                    + "\"truncated\":false}");
            case "read_file" -> textResult(fixture, range);
            default -> jsonResult(fixture, diffJson());
        }).when(fixture.toolService()).result("Bearer worker", EXECUTION);
        when(fixture.gateway().chat(any())).thenReturn(terminalReply());

        fixture.service().execute("Bearer worker", JOB, 1, RESULT, fixture.request());

        assertThat(submitted)
                .extracting(toolRequest -> toolRequest.path("tool").path("name").asText())
                .containsExactly("search_code", "read_file", "read_diff");
        JsonNode search = submitted.get(0).path("tool").path("arguments");
        assertThat(search.path("query").asText()).isEqualTo("진행 중만 보기");
        assertThat(search.path("roots")).hasSize(1);
        assertThat(search.path("roots").get(0).asText()).isEqualTo("src/Large.tsx");
        JsonNode read = submitted.get(1).path("tool").path("arguments");
        assertThat(read.path("path").asText()).isEqualTo("src/Large.tsx");
        assertThat(read.path("startLine").asInt()).isEqualTo(173);
        assertThat(read.path("endLine").asInt()).isEqualTo(209);
        ArgumentCaptor<ProviderChatRequest> routed =
                ArgumentCaptor.forClass(ProviderChatRequest.class);
        verify(fixture.gateway()).chat(routed.capture());
        JsonNode excerpts = mapper.readTree(firstUserMessage(routed.getValue()))
                .path("targetFileExcerpts");
        assertThat(excerpts).hasSize(1);
        assertThat(excerpts.get(0).path("startLine").asInt()).isEqualTo(173);
        assertThat(excerpts.get(0).path("endLine").asInt()).isEqualTo(209);
        assertThat(excerpts.get(0).path("content").asText()).isEqualTo(range);
    }

    /* A phrase two target files hold names neither of them; a comment match is not the screen. */
    @Test
    void aPhraseHeldByTwoFilesOrOnlyByACommentYieldsNoExcerpt() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        // Two whole files hold the phrase on a code line.
        StageFixture two = stageFixture(
                mapper, bindingPolicy(mapper), 3, ModelProvider.GOOGLE_GENAI,
                List.of(analysisNaming(mapper, "src/Small.java", "src/Twin.java")),
                "Rename 'updateUser' on the members screen.");
        answerToolsByRequest(two, new ArrayList<>(), toolRequest ->
                "read_file".equals(toolRequest.path("tool").path("name").asText())
                        ? textResult(two, SMALL_JAVA)
                        : jsonResult(two, diffJson()));
        when(two.gateway().chat(any())).thenReturn(terminalReply());
        two.service().execute("Bearer worker", JOB, 1, RESULT, two.request());
        ArgumentCaptor<ProviderChatRequest> routedTwo =
                ArgumentCaptor.forClass(ProviderChatRequest.class);
        verify(two.gateway()).chat(routedTwo.capture());
        assertThat(mapper.readTree(firstUserMessage(routedTwo.getValue()))
                .has("targetFileExcerpts")).isFalse();

        // One file holds the phrase, but only in a comment.
        StageFixture comment = stageFixture(
                mapper, bindingPolicy(mapper), 3, ModelProvider.GOOGLE_GENAI,
                List.of(analysisNaming(mapper, "src/Small.java")),
                "Rename 'updateUser' on the members screen.");
        answerToolsByRequest(comment, new ArrayList<>(), toolRequest ->
                "read_file".equals(toolRequest.path("tool").path("name").asText())
                        ? textResult(comment, "package demo;\n\n// updateUser lives elsewhere\n"
                                + "public class Small {\n    public void save() {\n    }\n}\n")
                        : jsonResult(comment, diffJson()));
        when(comment.gateway().chat(any())).thenReturn(terminalReply());
        comment.service().execute("Bearer worker", JOB, 1, RESULT, comment.request());
        ArgumentCaptor<ProviderChatRequest> routedComment =
                ArgumentCaptor.forClass(ProviderChatRequest.class);
        verify(comment.gateway()).chat(routedComment.capture());
        assertThat(mapper.readTree(firstUserMessage(routedComment.getValue()))
                .has("targetFileExcerpts")).isFalse();
    }

    /* Excerpts ride along on every answer, so past 8,000 characters only the first is kept. */
    @Test
    void excerptsStopAtTheCharacterCap() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        StringBuilder big = new StringBuilder("public class Big {\n");
        big.append("    public void alpha() {\n");
        for (int line = 0; line < 100; line++) {
            big.append("        int a").append(line).append(" = ").append("x".repeat(50)).append(";\n");
        }
        big.append("    public void beta() {\n");
        for (int line = 0; line < 100; line++) {
            big.append("        int b").append(line).append(" = ").append("y".repeat(50)).append(";\n");
        }
        big.append("}\n");
        StageFixture fixture = stageFixture(
                mapper, bindingPolicy(mapper), 3, ModelProvider.GOOGLE_GENAI,
                List.of(analysisNaming(mapper, "src/Big.java")),
                "Change 'alpha' and 'beta' on the screen.");
        answerToolsByRequest(fixture, new ArrayList<>(), toolRequest ->
                "read_file".equals(toolRequest.path("tool").path("name").asText())
                        ? textResult(fixture, big.toString())
                        : jsonResult(fixture, diffJson()));
        when(fixture.gateway().chat(any())).thenReturn(terminalReply());

        fixture.service().execute("Bearer worker", JOB, 1, RESULT, fixture.request());

        ArgumentCaptor<ProviderChatRequest> routed =
                ArgumentCaptor.forClass(ProviderChatRequest.class);
        verify(fixture.gateway()).chat(routed.capture());
        JsonNode excerpts = mapper.readTree(firstUserMessage(routed.getValue()))
                .path("targetFileExcerpts");
        assertThat(excerpts).hasSize(1);
        assertThat(excerpts.get(0).path("startLine").asInt()).isEqualTo(2);
        assertThat(excerpts.get(0).path("endLine").asInt()).isEqualTo(102);
        assertThat(excerpts.get(0).path("content").asText().length()).isLessThan(8_000);
    }

    @Test
    void quotedPhrasesComeInOrderWithoutRepeatsAndAtMostFour() {
        assertThat(CodingHandlerStageService.quotedPhrases(
                "Add '진행 중만 보기' next to \"지금 열리는 축제·행사\", then ‘진행 중만 보기’ again, "
                        + "“four”, 'five', and 'x'."))
                .containsExactly("진행 중만 보기", "지금 열리는 축제·행사", "four", "five");
        assertThat(CodingHandlerStageService.quotedPhrases("no quotes here")).isEmpty();
        assertThat(CodingHandlerStageService.quotedPhrases(null)).isEmpty();
    }

    private static final String SMALL_JAVA = "package demo;\n\npublic class Small {\n"
            + "    public void updateUser() {\n    }\n}\n";

    /** Submits are accepted and recorded; each result is built from the request it answers. */
    private static void answerToolsByRequest(
            StageFixture fixture, List<JsonNode> submitted,
            java.util.function.Function<JsonNode, CodingToolContract.ResultContent> results) {
        AtomicReference<JsonNode> last = new AtomicReference<>();
        when(fixture.toolService().submitForNode(eq("Bearer worker"), any(), eq("code")))
                .thenAnswer(invocation -> {
                    JsonNode toolRequest = invocation.getArgument(1);
                    submitted.add(toolRequest);
                    last.set(toolRequest);
                    return acceptedSubmit(fixture.submittedToolCall()).answer(invocation);
                });
        doAnswer(ignored -> results.apply(last.get()))
                .when(fixture.toolService()).result("Bearer worker", EXECUTION);
    }

    private static CodingToolContract.ResultContent textResult(StageFixture fixture, String text) {
        return new CodingToolContract.ResultContent(
                "1.0", UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
                fixture.submittedToolCall().get(),
                JOB, TRACE, "stage-tool.result", EXECUTION, "text/plain", text.length(),
                "sha256:" + "f".repeat(64), text);
    }

    private static CodingToolContract.ResultContent jsonResult(StageFixture fixture, String json) {
        return new CodingToolContract.ResultContent(
                "1.0", UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
                fixture.submittedToolCall().get(),
                JOB, TRACE, "stage-tool.result", EXECUTION, "application/json", json.length(),
                "sha256:" + "f".repeat(64), json);
    }

    private static String diffJson() {
        return "{\"workspaceId\":\"" + WORKSPACE + "\","
                + "\"baseSha\":\"" + BASE_SHA + "\","
                + "\"candidateSha\":\"" + BASE_SHA + "\","
                + "\"digest\":\"" + DIFF_DIGEST + "\","
                + "\"changedPaths\":[\"src/App.java\"]}";
    }

    /*
     * AI04-034 brake: Job 3c9062a8 read for all 24 answers of its first code round, never
     * edited, and failed at the turn limit after 218,644 input tokens. A first round that has
     * only read for the limit's worth of answers now ends there with the same failure code,
     * after the last reading answer's tool has run.
     */
    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void readingWithoutAnEditEndsTheFirstCodeRoundAtTheBrake(boolean retentionEnabled) {
        ObjectMapper mapper = new ObjectMapper();
        StageFixture fixture = stageFixture(
                mapper, bindingPolicy(mapper), 3, ModelProvider.GOOGLE_GENAI,
                List.of(), "Implement the approved change.", 3);
        org.springframework.test.util.ReflectionTestUtils.setField(fixture.service(), "smallReadHistoryRetentionEnabled", retentionEnabled);
        org.springframework.test.util.ReflectionTestUtils.setField(fixture.service(), "smallSearchHistoryRetentionEnabled", retentionEnabled);
        when(fixture.toolService().submitForNode(eq("Bearer worker"), any(), eq("code")))
                .thenAnswer(acceptedSubmit(fixture.submittedToolCall()));
        when(fixture.gateway().chat(any())).thenReturn(
                toolCallReply("read_file", READ_B, "{\"path\":\"src/App.java\"}"),
                toolCallReply("search_code", READ_C, "{\"query\":\"App\",\"scope\":\"src\"}"),
                toolCallReply("read_file", READ_D, "{\"path\":\"src/Other.java\"}"),
                terminalReply());

        assertThatThrownBy(() -> fixture.service().execute(
                "Bearer worker", JOB, 1, RESULT, fixture.request()))
                .isInstanceOfSatisfying(ProviderGatewayException.class, failure -> {
                    assertThat(failure.code())
                            .isEqualTo(ModelGatewayErrorCode.MODEL_RESPONSE_INVALID);
                    assertThat(failure.getMessage()).contains("3 answers without an edit");
                });
        verify(fixture.gateway(), times(3)).chat(any());
        // The stage's own read_diff, then the three reading answers' tools.
        verify(fixture.toolService(), times(4))
                .submitForNode(eq("Bearer worker"), any(), eq("code"));
    }

    /* An edit before the brake is reached switches it off for the rest of the round. */
    @Test
    void anEditBeforeTheBrakeKeepsTheCodeRoundGoing() {
        ObjectMapper mapper = new ObjectMapper();
        StageFixture fixture = stageFixture(
                mapper, bindingPolicy(mapper), 3, ModelProvider.GOOGLE_GENAI,
                List.of(), "Implement the approved change.", 3);
        when(fixture.toolService().submitForNode(eq("Bearer worker"), any(), eq("code")))
                .thenAnswer(acceptedSubmit(fixture.submittedToolCall()));
        when(fixture.gateway().chat(any())).thenReturn(
                toolCallReply("read_file", READ_B, "{\"path\":\"src/App.java\"}"),
                toolCallReply("read_file", READ_C, "{\"path\":\"src/Other.java\"}"),
                toolCallReply("apply_patch", READ_D, "{\"patch\":\"diff\"}"),
                toolCallReply("read_file", READ_E, "{\"path\":\"src/App.java\"}"),
                toolCallReply("read_file", READ_F, "{\"path\":\"src/Other.java\"}"),
                terminalReply());

        CodingHandlerContract.StageExecutionResponse response = fixture.service().execute(
                "Bearer worker", JOB, 1, RESULT, fixture.request());

        assertThat(response.resultPort()).isEqualTo("completed");
        verify(fixture.gateway(), times(6)).chat(any());
    }

    /* A zero limit disables the brake; only the turn limit bounds the round. */
    @Test
    void aZeroBrakeLeavesOnlyTheTurnLimit() {
        ObjectMapper mapper = new ObjectMapper();
        StageFixture fixture = stageFixture(
                mapper, bindingPolicy(mapper), 3, ModelProvider.GOOGLE_GENAI,
                List.of(), "Implement the approved change.", 0);
        when(fixture.toolService().submitForNode(eq("Bearer worker"), any(), eq("code")))
                .thenAnswer(acceptedSubmit(fixture.submittedToolCall()));
        when(fixture.gateway().chat(any())).thenReturn(
                toolCallReply("read_file", READ_B, "{\"path\":\"src/App.java\"}"),
                toolCallReply("read_file", READ_C, "{\"path\":\"src/Other.java\"}"),
                toolCallReply("read_file", READ_D, "{\"path\":\"src/App.java\"}"),
                toolCallReply("read_file", READ_E, "{\"path\":\"src/Other.java\"}"),
                terminalReply());

        CodingHandlerContract.StageExecutionResponse response = fixture.service().execute(
                "Bearer worker", JOB, 1, RESULT, fixture.request());

        assertThat(response.resultPort()).isEqualTo("completed");
        verify(fixture.gateway(), times(5)).chat(any());
    }

    /* A rework round starts from a diff that already exists, so the brake leaves it alone. */
    @Test
    void theBrakeLeavesAReworkRoundAlone() {
        ObjectMapper mapper = new ObjectMapper();
        CodingHandlerContract.HandlerResultResponse firstRound =
                new CodingHandlerContract.HandlerResultResponse(
                        "1.0", UUID.fromString("12121212-1212-4121-8121-121212121212"),
                        JOB, TRACE, 1, "coding.code",
                        CodingHandlerContract.ResultType.CANDIDATE, "completed",
                        WORKSPACE, BASE_SHA, DIFF_DIGEST, null,
                        mapper.createObjectNode().put("summary", "first round"), NOW);
        StageFixture fixture = stageFixture(
                mapper, bindingPolicy(mapper), 3, ModelProvider.GOOGLE_GENAI,
                List.of(firstRound), "Implement the approved change.", 3);
        when(fixture.toolService().submitForNode(eq("Bearer worker"), any(), eq("code")))
                .thenAnswer(acceptedSubmit(fixture.submittedToolCall()));
        when(fixture.gateway().chat(any())).thenReturn(
                toolCallReply("read_file", READ_B, "{\"path\":\"src/App.java\"}"),
                toolCallReply("read_file", READ_C, "{\"path\":\"src/Other.java\"}"),
                toolCallReply("read_file", READ_D, "{\"path\":\"src/App.java\"}"),
                terminalReply());

        CodingHandlerContract.StageExecutionResponse response = fixture.service().execute(
                "Bearer worker", JOB, 1, RESULT, fixture.request());

        assertThat(response.resultPort()).isEqualTo("completed");
        verify(fixture.gateway(), times(4)).chat(any());
    }

    /*
     * AI04-034 carry-over: on b3a872c3 and 543eb70f the second and third code rounds took about
     * two thirds of the code stage's input tokens, because each began from the request alone
     * and searched out the same files again. A rework round's first message now carries the
     * diff the earlier round left, read by the stage's own read_diff before the first answer.
     */
    @Test
    void aReworkRoundStartsFromTheDiffTheEarlierRoundLeft() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        CodingHandlerContract.HandlerResultResponse firstRound =
                new CodingHandlerContract.HandlerResultResponse(
                        "1.0", UUID.fromString("12121212-1212-4121-8121-121212121212"),
                        JOB, TRACE, 1, "coding.code",
                        CodingHandlerContract.ResultType.CANDIDATE, "completed",
                        WORKSPACE, BASE_SHA, DIFF_DIGEST, null,
                        mapper.createObjectNode().put("summary", "first round"), NOW);
        StageFixture fixture = stageFixture(
                mapper, bindingPolicy(mapper), 3, ModelProvider.GOOGLE_GENAI,
                List.of(firstRound), "Implement the approved change.");
        String diff = "diff --git a/src/App.java b/src/App.java\n+    int changed = 1;\n";
        answerToolsByRequest(fixture, new ArrayList<>(),
                toolRequest -> jsonResult(fixture, diffJsonWith(diff)));
        when(fixture.gateway().chat(any())).thenReturn(terminalReply());

        fixture.service().execute("Bearer worker", JOB, 1, RESULT, fixture.request());

        ArgumentCaptor<ProviderChatRequest> routed =
                ArgumentCaptor.forClass(ProviderChatRequest.class);
        verify(fixture.gateway()).chat(routed.capture());
        assertThat(mapper.readTree(firstUserMessage(routed.getValue()))
                .path("currentDiff").asText()).isEqualTo(diff);
        assertThat(routed.getValue().messages().get(0).content())
                .contains("currentDiff, when present");
    }

    /* A first round's diff is empty, so its first message carries no currentDiff. */
    @Test
    void aFirstRoundCarriesNoCurrentDiff() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        StageFixture fixture = stageFixture(mapper);
        answerToolsByRequest(fixture, new ArrayList<>(),
                toolRequest -> jsonResult(fixture, diffJsonWith("")));
        when(fixture.gateway().chat(any())).thenReturn(terminalReply());

        fixture.service().execute("Bearer worker", JOB, 1, RESULT, fixture.request());

        ArgumentCaptor<ProviderChatRequest> routed =
                ArgumentCaptor.forClass(ProviderChatRequest.class);
        verify(fixture.gateway()).chat(routed.capture());
        assertThat(mapper.readTree(firstUserMessage(routed.getValue()))
                .has("currentDiff")).isFalse();
    }

    /* A refused stage read_diff leaves nothing to carry; the model reads the diff itself. */
    @Test
    void aRefusedStageReadDiffCarriesNoCurrentDiff() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        StageFixture fixture = stageFixture(mapper);
        when(fixture.toolService().submitForNode(eq("Bearer worker"), any(), eq("code")))
                .thenThrow(new CodingToolException(
                        "TOOL_EXECUTION_FAILED", "The MCP coding tool refused the call.",
                        org.springframework.http.HttpStatus.BAD_GATEWAY))
                .thenAnswer(acceptedSubmit(fixture.submittedToolCall()));
        when(fixture.gateway().chat(any())).thenReturn(
                toolCallReply("read_diff", DIFF_CALL, "{}"),
                terminalReply());

        fixture.service().execute("Bearer worker", JOB, 1, RESULT, fixture.request());

        ArgumentCaptor<ProviderChatRequest> routed =
                ArgumentCaptor.forClass(ProviderChatRequest.class);
        verify(fixture.gateway(), times(2)).chat(routed.capture());
        assertThat(mapper.readTree(firstUserMessage(routed.getAllValues().get(0)))
                .has("currentDiff")).isFalse();
    }

    /* The first message is re-sent with every answer, so a long diff is cut at 8,000 characters. */
    @Test
    void aLongCurrentDiffIsCutAtTheCap() {
        ObjectMapper mapper = new ObjectMapper();
        String longDiff = "+" + "x".repeat(8_999);
        String carried = CodingHandlerStageService.currentDiffText(
                mapper.createObjectNode().put("diff", longDiff));
        assertThat(carried).startsWith(longDiff.substring(0, 8_000));
        assertThat(carried).contains("(truncated: the diff is 9000 characters");
        assertThat(carried.length()).isLessThan(8_100);
        assertThat(CodingHandlerStageService.currentDiffText(null)).isNull();
        assertThat(CodingHandlerStageService.currentDiffText(
                mapper.createObjectNode().put("diff", ""))).isNull();
    }

    private static String diffJsonWith(String diff) {
        return "{\"workspaceId\":\"" + WORKSPACE + "\","
                + "\"baseSha\":\"" + BASE_SHA + "\","
                + "\"candidateSha\":\"" + BASE_SHA + "\","
                + "\"digest\":\"" + DIFF_DIGEST + "\","
                + "\"changedPaths\":[\"src/App.java\"],"
                + "\"diff\":" + new ObjectMapper().valueToTree(diff) + "}";
    }

    /*
     * AI04-034 fence search: Jobs 3d4b364e and 3c9062a8 left TourPortal.tsx - the file every
     * phrase of the request lives in - out of the target files, and the excerpt came back
     * empty. A phrase no target holds is now searched in the Job's fence folders, and the one
     * file holding it on a code line is read and excerpted like a target.
     */
    @Test
    void aPhraseNoTargetHoldsIsFoundInTheFenceAndExcerpted() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        StageFixture fixture = stageFixture(
                mapper, bindingPolicy(mapper), 3, ModelProvider.GOOGLE_GENAI,
                List.of(analysisNaming(mapper, "src/Small.java")),
                "'진행 중만 보기' 버튼을 추가해줘");
        when(fixture.selections().jobSnapshot(JOB)).thenReturn(List.of(
                "frontend:src/features/site", "frontend:src/features/site"));
        String screen = "export function PortalHome() {\n  const shown = 1;\n"
                + "  return <button>진행 중만 보기</button>;\n}\n";
        List<JsonNode> submitted = new ArrayList<>();
        answerToolsByRequest(fixture, submitted, toolRequest -> {
            String name = toolRequest.path("tool").path("name").asText();
            String path = toolRequest.path("tool").path("arguments").path("path").asText("");
            if ("search_code".equals(name)) {
                return jsonResult(fixture, searchJson(false,
                        "src/features/site/Portal.tsx", 3,
                        "  return <button>진행 중만 보기</button>;"));
            }
            if ("read_file".equals(name)) {
                return textResult(fixture, "src/Small.java".equals(path) ? SMALL_JAVA : screen);
            }
            return jsonResult(fixture, diffJson());
        });
        when(fixture.gateway().chat(any())).thenReturn(terminalReply());

        fixture.service().execute("Bearer worker", JOB, 1, RESULT, fixture.request());

        // The target's outline read, one search (the repeated folder counts once), one read of
        // the file the search named, then the stage's read_diff.
        assertThat(submitted)
                .extracting(toolRequest -> toolRequest.path("tool").path("name").asText())
                .containsExactly("read_file", "search_code", "read_file", "read_diff");
        JsonNode search = submitted.get(1).path("tool").path("arguments");
        assertThat(search.path("query").asText()).isEqualTo("진행 중만 보기");
        assertThat(search.path("roots").get(0).asText()).isEqualTo("src/features/site");
        assertThat(submitted.get(2).path("tool").path("arguments").path("path").asText())
                .isEqualTo("src/features/site/Portal.tsx");
        ArgumentCaptor<ProviderChatRequest> routed =
                ArgumentCaptor.forClass(ProviderChatRequest.class);
        verify(fixture.gateway()).chat(routed.capture());
        JsonNode excerpts = mapper.readTree(firstUserMessage(routed.getValue()))
                .path("targetFileExcerpts");
        assertThat(excerpts).hasSize(1);
        assertThat(excerpts.get(0).path("path").asText())
                .isEqualTo("src/features/site/Portal.tsx");
        assertThat(excerpts.get(0).path("startLine").asInt()).isEqualTo(1);
        assertThat(excerpts.get(0).path("content").asText()).contains("진행 중만 보기");
    }

    /*
     * Job 100af538 (2026-09-15): '진행 중' sat on code lines of portal-meta.ts and its test.
     * Counting the test as a second holder dropped the excerpt, so the model never saw
     * festivalBadge and rewrote its date check with the UTC slip the helper avoids. A test is
     * not the screen: the one source file left holds the phrase and is excerpted.
     */
    @Test
    void aTestSharingThePhraseDoesNotHideTheOneSourceFileHoldingIt() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        StageFixture fixture = stageFixture(
                mapper, bindingPolicy(mapper), 3, ModelProvider.GOOGLE_GENAI,
                List.of(analysisNaming(mapper, "src/Small.java")),
                "'진행 중' 카드만 남겨줘");
        when(fixture.selections().jobSnapshot(JOB))
                .thenReturn(List.of("frontend:src/features/site"));
        String meta = "export function festivalBadge(start: string, end: string) {\n"
                + "  const now = today()\n  if (now >= start) return '진행 중'\n"
                + "  return 'D-1'\n}\n";
        List<JsonNode> submitted = new ArrayList<>();
        answerToolsByRequest(fixture, submitted, toolRequest -> {
            String name = toolRequest.path("tool").path("name").asText();
            String path = toolRequest.path("tool").path("arguments").path("path").asText("");
            if ("search_code".equals(name)) {
                return jsonResult(fixture,
                        "{\"matches\":[{\"path\":\"src/features/site/portal-meta.test.ts\","
                                + "\"line\":98,\"column\":3,"
                                + "\"preview\":\"  expect(badge).toBe('진행 중')\"},"
                                + "{\"path\":\"src/features/site/portal-meta.ts\",\"line\":3,"
                                + "\"column\":3,\"preview\":\"  if (now >= start) return '진행 중'\"}],"
                                + "\"truncated\":false}");
            }
            if ("read_file".equals(name)) {
                return textResult(fixture, "src/Small.java".equals(path) ? SMALL_JAVA : meta);
            }
            return jsonResult(fixture, diffJson());
        });
        when(fixture.gateway().chat(any())).thenReturn(terminalReply());

        fixture.service().execute("Bearer worker", JOB, 1, RESULT, fixture.request());

        assertThat(submitted)
                .extracting(toolRequest -> toolRequest.path("tool").path("name").asText())
                .containsExactly("read_file", "search_code", "read_file", "read_diff");
        assertThat(submitted.get(2).path("tool").path("arguments").path("path").asText())
                .isEqualTo("src/features/site/portal-meta.ts");
        ArgumentCaptor<ProviderChatRequest> routed =
                ArgumentCaptor.forClass(ProviderChatRequest.class);
        verify(fixture.gateway()).chat(routed.capture());
        JsonNode excerpts = mapper.readTree(firstUserMessage(routed.getValue()))
                .path("targetFileExcerpts");
        assertThat(excerpts).hasSize(1);
        assertThat(excerpts.get(0).path("path").asText())
                .isEqualTo("src/features/site/portal-meta.ts");
        assertThat(excerpts.get(0).path("content").asText()).contains("festivalBadge");
    }

    /*
     * Job b960265f (2026-09-15): the target PortalResultCard.tsx held '진행 중' only on the middle
     * line of a JSX block comment (frontend c3345be). Read one line at a time that line looked
     * like code, the phrase looked held by a target, and the fence search that finds
     * festivalBadge never ran - the model then invented isOngoingFestival. The whole target is
     * now walked with its comment state, so the fence search runs and portal-meta.ts is excerpted.
     */
    @Test
    void aPhraseOnlyInsideAMultiLineJsxCommentOfATargetStillSearchesTheFence() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        StageFixture fixture = stageFixture(
                mapper, bindingPolicy(mapper), 3, ModelProvider.GOOGLE_GENAI,
                List.of(analysisNaming(mapper, "src/features/site/PortalResultCard.tsx")),
                "'진행 중' 표시가 붙은 카드만 남겨줘");
        when(fixture.selections().jobSnapshot(JOB))
                .thenReturn(List.of("frontend:src/features/site"));
        String card = "export function PortalResultCard() {\n"
                + "  return <div>\n"
                + "        {/* 카테고리 뱃지와 **모양으로** 갈린다 — 저쪽은 테두리형, 이쪽은 채움형이다. 둘 다\n"
                + "            테두리형이던 때는 두 카드가 한눈에 거의 같아 보여서, 진행 중 행사와 종료된 행사를\n"
                + "            나란히 놓아도 사람이 차이를 못 짚었다. 색만으로 구분하지 않으려고 \"종료된 행사\"\n"
                + "            참고 정보라 오류가 아니다. */}\n"
                + "        {ended && <span>종료된 행사</span>}\n"
                + "  </div>\n}\n";
        String meta = "export function festivalBadge(start: string, end: string) {\n"
                + "  const now = today()\n  if (now >= start) return '진행 중'\n"
                + "  return 'D-1'\n}\n";
        List<JsonNode> submitted = new ArrayList<>();
        answerToolsByRequest(fixture, submitted, toolRequest -> {
            String name = toolRequest.path("tool").path("name").asText();
            String path = toolRequest.path("tool").path("arguments").path("path").asText("");
            if ("search_code".equals(name)) {
                return jsonResult(fixture, searchJson(false,
                        "src/features/site/portal-meta.ts", 3, "  if (now >= start) return '진행 중'"));
            }
            if ("read_file".equals(name)) {
                return textResult(fixture,
                        "src/features/site/PortalResultCard.tsx".equals(path) ? card : meta);
            }
            return jsonResult(fixture, diffJson());
        });
        when(fixture.gateway().chat(any())).thenReturn(terminalReply());

        fixture.service().execute("Bearer worker", JOB, 1, RESULT, fixture.request());

        assertThat(submitted)
                .extracting(toolRequest -> toolRequest.path("tool").path("name").asText())
                .containsExactly("read_file", "search_code", "read_file", "read_diff");
        assertThat(submitted.get(1).path("tool").path("arguments").path("query").asText())
                .isEqualTo("진행 중");
        assertThat(submitted.get(2).path("tool").path("arguments").path("path").asText())
                .isEqualTo("src/features/site/portal-meta.ts");
        ArgumentCaptor<ProviderChatRequest> routed =
                ArgumentCaptor.forClass(ProviderChatRequest.class);
        verify(fixture.gateway()).chat(routed.capture());
        JsonNode excerpts = mapper.readTree(firstUserMessage(routed.getValue()))
                .path("targetFileExcerpts");
        assertThat(excerpts).hasSize(1);
        assertThat(excerpts.get(0).path("path").asText())
                .isEqualTo("src/features/site/portal-meta.ts");
        assertThat(excerpts.get(0).path("content").asText()).contains("festivalBadge");
    }

    /* The comment shape of frontend PortalResultCard.tsx:53-61 (c3345be), line for line. */
    @Test
    void aPhraseInsideAMultiLineJsxCommentIsNotOnACodeLine() {
        String[] lines = {
                "      {(badge != null || ended) && <div className=\"flex flex-wrap gap-1.5\">",
                "        {/* 카테고리 뱃지와 **모양으로** 갈린다 — 저쪽은 테두리형, 이쪽은 채움형이다. 둘 다",
                "            테두리형이던 때는 두 카드가 한눈에 거의 같아 보여서, 진행 중 행사와 종료된 행사를",
                "            나란히 놓아도 사람이 차이를 못 짚었다. 색만으로 구분하지 않으려고 \"종료된 행사\"",
                "            참고 정보라 오류가 아니다. */}",
                "        {ended && <span className=\"rounded-md\">종료된 행사</span>}",
                "      </div>}"};

        // One line alone is fooled by the middle line - the reason the whole file is walked.
        assertThat(CodingHandlerStageService.isCodeLine(lines[2])).isTrue();
        assertThat(CodingHandlerStageService.firstCodeLineHolding(lines, "진행 중")).isZero();
        // After the comment closes, the same kind of text is code again.
        assertThat(CodingHandlerStageService.firstCodeLineHolding(lines, "종료된 행사")).isEqualTo(6);
    }

    @Test
    void commentTrackingKeepsCodeBesideCommentsAndIgnoresMarksInStringsAndLineComments() {
        // Code before a comment that opens on the same line is still code.
        assertThat(CodingHandlerStageService.firstCodeLineHolding(new String[] {
                "  if (now >= start) return '진행 중' /* badge", "  still comment */"}, "진행 중"))
                .isEqualTo(1);
        // A JSDoc block hides its body; the declaration after it is found.
        assertThat(CodingHandlerStageService.firstCodeLineHolding(new String[] {
                "/**", " * 진행 중 뱃지", " */", "const label = '진행 중'"}, "진행 중"))
                .isEqualTo(4);
        // A path string holding /* opens no comment, so the next line stays code.
        assertThat(CodingHandlerStageService.firstCodeLineHolding(new String[] {
                "const pages = import.meta.glob('./**/*.tsx')", "<p>진행 중</p>"}, "진행 중"))
                .isEqualTo(2);
        // Nor does a /* after //.
        assertThat(CodingHandlerStageService.firstCodeLineHolding(new String[] {
                "// see /* the badge helper", "<p>진행 중</p>"}, "진행 중"))
                .isEqualTo(2);
        // A line comment holding the phrase is still not code.
        assertThat(CodingHandlerStageService.firstCodeLineHolding(new String[] {
                "// 진행 중", "const shown = 1"}, "진행 중"))
                .isZero();
    }

    /*
     * Two source files name neither surely, a phrase only a test holds is not the screen, and
     * a cut-off result may hide a second file - none of them adds an excerpt. Only the two-file
     * case re-reads its candidates, and both still hold the phrase on a code line.
     */
    @Test
    void aFenceMatchInTwoFilesOnlyInATestOrCutOffIsNotUsed() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<String> searchResults = List.of(
                "{\"matches\":[{\"path\":\"src/features/site/portal-meta.ts\",\"line\":100,"
                        + "\"column\":3,\"preview\":\"  if (now >= start) return '진행 중'\"},"
                        + "{\"path\":\"src/features/site/TourPortal.tsx\",\"line\":40,"
                        + "\"column\":3,\"preview\":\"  const label = '진행 중'\"}],"
                        + "\"truncated\":false}",
                searchJson(false, "src/features/site/Portal.test.tsx", 12,
                        "  expect(screen.getByText('진행 중')).toBeVisible()"),
                searchJson(true, "src/features/site/portal-meta.ts", 100,
                        "  if (now >= start) return '진행 중'"));
        List<List<String>> expectedTools = List.of(
                List.of("read_file", "search_code", "read_file", "read_file", "read_diff"),
                List.of("read_file", "search_code", "read_diff"),
                List.of("read_file", "search_code", "read_diff"));
        String holding = "export function label() {\n  return '진행 중'\n}\n";
        for (int caseIndex = 0; caseIndex < searchResults.size(); caseIndex++) {
            String searchResult = searchResults.get(caseIndex);
            StageFixture fixture = stageFixture(
                    mapper, bindingPolicy(mapper), 3, ModelProvider.GOOGLE_GENAI,
                    List.of(analysisNaming(mapper, "src/Small.java")),
                    "'진행 중' 카드만 남겨줘");
            when(fixture.selections().jobSnapshot(JOB))
                    .thenReturn(List.of("frontend:src/features/site"));
            List<JsonNode> submitted = new ArrayList<>();
            answerToolsByRequest(fixture, submitted, toolRequest -> {
                String name = toolRequest.path("tool").path("name").asText();
                String path = toolRequest.path("tool").path("arguments").path("path").asText("");
                if ("search_code".equals(name)) {
                    return jsonResult(fixture, searchResult);
                }
                if ("read_file".equals(name)) {
                    return textResult(fixture, "src/Small.java".equals(path) ? SMALL_JAVA : holding);
                }
                return jsonResult(fixture, diffJson());
            });
            when(fixture.gateway().chat(any())).thenReturn(terminalReply());

            fixture.service().execute("Bearer worker", JOB, 1, RESULT, fixture.request());

            assertThat(submitted)
                    .extracting(toolRequest -> toolRequest.path("tool").path("name").asText())
                    .containsExactlyElementsOf(expectedTools.get(caseIndex));
            ArgumentCaptor<ProviderChatRequest> routed =
                    ArgumentCaptor.forClass(ProviderChatRequest.class);
            verify(fixture.gateway()).chat(routed.capture());
            assertThat(mapper.readTree(firstUserMessage(routed.getValue()))
                    .has("targetFileExcerpts")).isFalse();
        }
    }

    /*
     * The fence search this request really gets (frontend 34e797c): '진행 중' comes back on
     * PortalResultCard.tsx:56, the middle line of a JSX comment, and on portal-meta.ts:148. One
     * preview line cannot show the comment, so the two looked equally held and nothing was
     * excerpted. The ambiguous candidates are now walked whole - the target from memory, the
     * other read once and reused for the excerpt - and only portal-meta.ts holds it in code.
     */
    @Test
    void anAmbiguousFenceMatchIsSettledByWalkingTheCandidateFilesWhole() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        StageFixture fixture = stageFixture(
                mapper, bindingPolicy(mapper), 3, ModelProvider.GOOGLE_GENAI,
                List.of(analysisNaming(mapper, "src/features/site/PortalResultCard.tsx")),
                "'진행 중' 표시가 붙은 카드만 남겨줘");
        when(fixture.selections().jobSnapshot(JOB))
                .thenReturn(List.of("frontend:src/features/site"));
        String card = "export function PortalResultCard() {\n"
                + "  return <div>\n"
                + "        {/* 카테고리 뱃지와 **모양으로** 갈린다 — 저쪽은 테두리형, 이쪽은 채움형이다. 둘 다\n"
                + "            테두리형이던 때는 두 카드가 한눈에 거의 같아 보여서, 진행 중 행사와 종료된 행사를\n"
                + "            참고 정보라 오류가 아니다. */}\n"
                + "  </div>\n}\n";
        String meta = "export function festivalBadge(start: string, end: string) {\n"
                + "  const now = today()\n  if (now >= start) return '진행 중'\n"
                + "  return 'D-1'\n}\n";
        List<JsonNode> submitted = new ArrayList<>();
        answerToolsByRequest(fixture, submitted, toolRequest -> {
            String name = toolRequest.path("tool").path("name").asText();
            String path = toolRequest.path("tool").path("arguments").path("path").asText("");
            if ("search_code".equals(name)) {
                return jsonResult(fixture,
                        "{\"matches\":[{\"path\":\"src/features/site/PortalResultCard.tsx\","
                                + "\"line\":4,\"column\":39,\"preview\":\"            테두리형이던 때는 "
                                + "두 카드가 한눈에 거의 같아 보여서, 진행 중 행사와 종료된 행사를\"},"
                                + "{\"path\":\"src/features/site/portal-meta.ts\",\"line\":3,"
                                + "\"column\":3,\"preview\":\"  if (now >= start) return '진행 중'\"}],"
                                + "\"truncated\":false}");
            }
            if ("read_file".equals(name)) {
                return textResult(fixture,
                        "src/features/site/PortalResultCard.tsx".equals(path) ? card : meta);
            }
            return jsonResult(fixture, diffJson());
        });
        when(fixture.gateway().chat(any())).thenReturn(terminalReply());

        fixture.service().execute("Bearer worker", JOB, 1, RESULT, fixture.request());

        // The target's own read, the fence search, one read of portal-meta.ts, read_diff.
        assertThat(submitted)
                .extracting(toolRequest -> toolRequest.path("tool").path("name").asText())
                .containsExactly("read_file", "search_code", "read_file", "read_diff");
        assertThat(submitted.get(2).path("tool").path("arguments").path("path").asText())
                .isEqualTo("src/features/site/portal-meta.ts");
        ArgumentCaptor<ProviderChatRequest> routed =
                ArgumentCaptor.forClass(ProviderChatRequest.class);
        verify(fixture.gateway()).chat(routed.capture());
        JsonNode excerpts = mapper.readTree(firstUserMessage(routed.getValue()))
                .path("targetFileExcerpts");
        assertThat(excerpts).hasSize(1);
        assertThat(excerpts.get(0).path("path").asText())
                .isEqualTo("src/features/site/portal-meta.ts");
        assertThat(excerpts.get(0).path("startLine").asInt()).isEqualTo(1);
        assertThat(excerpts.get(0).path("content").asText()).contains("festivalBadge");
    }

    /* Without a fence the phrase is searched from the repository root. */
    @Test
    void withoutAFenceThePhraseIsSearchedFromTheRepositoryRoot() {
        ObjectMapper mapper = new ObjectMapper();
        StageFixture fixture = stageFixture(
                mapper, bindingPolicy(mapper), 3, ModelProvider.GOOGLE_GENAI,
                List.of(analysisNaming(mapper, "src/Small.java")),
                "'진행 중만 보기' 버튼을 추가해줘");
        List<JsonNode> submitted = new ArrayList<>();
        answerToolsByRequest(fixture, submitted, toolRequest ->
                "read_file".equals(toolRequest.path("tool").path("name").asText())
                        ? textResult(fixture, SMALL_JAVA)
                        : jsonResult(fixture, diffJson()));
        when(fixture.gateway().chat(any())).thenReturn(terminalReply());

        fixture.service().execute("Bearer worker", JOB, 1, RESULT, fixture.request());

        assertThat(submitted)
                .extracting(toolRequest -> toolRequest.path("tool").path("name").asText())
                .containsExactly("read_file", "search_code", "read_diff");
        assertThat(submitted.get(1).path("tool").path("arguments").path("roots").get(0).asText())
                .isEqualTo(".");
    }

    @Test
    void fenceFoldersAreDistinctCappedAndFallBackToTheRootAndTestFilesAreRecognised() {
        assertThat(CodingHandlerStageService.fenceFolders(List.of(
                "frontend:src/a", "backend:src/b", "frontend:src/a", "frontend:src/c",
                "frontend:src/d")))
                .containsExactly("src/a", "src/b", "src/c");
        assertThat(CodingHandlerStageService.fenceFolders(List.of())).containsExactly(".");
        assertThat(CodingHandlerStageService.fenceFolders(null)).containsExactly(".");
        assertThat(CodingHandlerStageService.isTestFile("src/features/site/portal-meta.test.ts"))
                .isTrue();
        assertThat(CodingHandlerStageService.isTestFile("src/main/java/demo/FooTest.java"))
                .isTrue();
        assertThat(CodingHandlerStageService.isTestFile("src/test/java/demo/Foo.java")).isTrue();
        assertThat(CodingHandlerStageService.isTestFile("src/features/site/TourPortal.tsx"))
                .isFalse();
    }

    private static String searchJson(boolean truncated, String path, int line, String preview) {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode result = mapper.createObjectNode();
        result.putArray("matches").addObject()
                .put("path", path).put("line", line).put("column", 1).put("preview", preview);
        result.put("truncated", truncated);
        return result.toString();
    }

    /** A feasible analysis naming the given target files, as approval 1 stored it. */
    private static CodingHandlerContract.HandlerResultResponse analysisNaming(
            ObjectMapper mapper, String... targetFiles) {
        ObjectNode payload = mapper.createObjectNode().put("planSummary", "고칩니다.");
        payload.putArray("acceptanceCriteria").add("완료한다");
        ArrayNode targets = payload.putArray("targetFiles");
        for (String target : targetFiles) {
            targets.add(target);
        }
        return new CodingHandlerContract.HandlerResultResponse(
                "1.0", UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
                JOB, TRACE, 1, "coding.analyze",
                CodingHandlerContract.ResultType.ANALYSIS, "feasible",
                WORKSPACE, null, null, null, payload, NOW);
    }

    /** The context the stage opens with: the first user message of a request. */
    private static String firstUserMessage(ProviderChatRequest request) {
        return request.messages().stream()
                .filter(message -> message.role() == ProviderChatMessage.Role.USER)
                .findFirst()
                .orElseThrow()
                .content();
    }

    @Test
    void aZeroFoldDepthLeavesEveryToolBodyInPlace() {
        ObjectMapper mapper = new ObjectMapper();
        StageFixture fixture = stageFixture(
                mapper, bindingPolicy(mapper), 0, ModelProvider.ANTHROPIC);
        when(fixture.toolService().submitForNode(eq("Bearer worker"), any(), eq("code")))
                .thenAnswer(acceptedSubmit(fixture.submittedToolCall()));
        when(fixture.gateway().chat(any())).thenReturn(
                toolCallReply(ModelProvider.ANTHROPIC,
                        "read_diff", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "{}"),
                threeReadsThenDone(ModelProvider.ANTHROPIC));

        fixture.service().execute("Bearer worker", JOB, 1, RESULT, fixture.request());

        ArgumentCaptor<ProviderChatRequest> routed =
                ArgumentCaptor.forClass(ProviderChatRequest.class);
        verify(fixture.gateway(), times(5)).chat(routed.capture());
        assertThat(toolBodies(routed.getAllValues().get(4)))
                .hasSize(4)
                .allSatisfy(body -> assertThat(body).contains(DIFF_DIGEST).doesNotContain("folded"));
        assertThat(routed.getAllValues().get(0).messages().get(0).content())
                .doesNotContain("folded to a short note");
    }

    /**
     * Gemini refused a folded conversation until AI04-027. The shared adapter kept each
     * turn's thought signatures under a key hashed from the whole earlier conversation, so
     * folding one old body changed the key of every call after it, the signatures came back
     * empty, and Gemini refused the unsigned calls - measured on 2026-09-11 as
     * MODEL_RESPONSE_INVALID on the first folded turn. The adapter now stores each signature
     * under the tool call id it was issued with, which a fold does not touch, so the code
     * stage folds for a Gemini primary binding like it does for every other provider -
     * measured on Job 99748158: nineteen turns, no provider refusal, and the code stage's
     * input down from 263,279 to 136,770 tokens.
     */
    @Test
    void aGeminiPrimaryBindingFoldsToolHistoryLikeEveryOtherProvider() {
        ObjectMapper mapper = new ObjectMapper();
        StageFixture fixture = stageFixture(
                mapper, bindingPolicy(mapper), 1, ModelProvider.GOOGLE_GENAI);
        when(fixture.toolService().submitForNode(eq("Bearer worker"), any(), eq("code")))
                .thenAnswer(acceptedSubmit(fixture.submittedToolCall()));
        when(fixture.gateway().chat(any())).thenReturn(
                toolCallReply("read_diff", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "{}"),
                threeReadsThenDone());

        fixture.service().execute("Bearer worker", JOB, 1, RESULT, fixture.request());

        ArgumentCaptor<ProviderChatRequest> routed =
                ArgumentCaptor.forClass(ProviderChatRequest.class);
        verify(fixture.gateway(), times(5)).chat(routed.capture());
        // Fifth request: read_diff, two folded reads, and the search that arrived last.
        // read_diff is never folded.
        List<String> fifth = toolBodies(routed.getAllValues().get(4));
        assertThat(fifth).hasSize(4);
        assertThat(fifth.get(0)).contains(DIFF_DIGEST);
        assertThat(fifth.get(1)).contains("folded").doesNotContain(DIFF_DIGEST);
        assertThat(fifth.get(2)).contains("folded").doesNotContain(DIFF_DIGEST);
        assertThat(fifth.get(3)).contains(DIFF_DIGEST);
        assertThat(routed.getAllValues().get(0).messages().get(0).content())
                .contains("Results from your last 1 reading answers", "up to 6 read_file/search_code bodies");
    }

    @Test
    void handsAToolRefusalBackToTheModelInsteadOfEndingTheJob() {
        ObjectMapper mapper = new ObjectMapper();
        StageFixture fixture = stageFixture(mapper);
        // The refused call never ran, so the loop must survive it: the refusal reason is
        // handed back and the corrected exchange finishes the stage. The first submit is
        // the stage's own read_diff.
        when(fixture.toolService().submitForNode(
                eq("Bearer worker"), any(), eq("code")))
                .thenAnswer(acceptedSubmit(fixture.submittedToolCall()))
                .thenThrow(new CodingToolException(
                        "TOOL_RESULT_NOT_READY",
                        "read_diff must establish the current diff digest first.",
                        org.springframework.http.HttpStatus.CONFLICT))
                .thenAnswer(acceptedSubmit(fixture.submittedToolCall()));
        when(fixture.gateway().chat(any())).thenReturn(
                toolCallReply("apply_patch", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                        "{\"patch\":\"diff\"}"),
                toolCallReply("read_diff", "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", "{}"),
                terminalReply());

        CodingHandlerContract.StageExecutionResponse response = fixture.service().execute(
                "Bearer worker", JOB, 1, RESULT, fixture.request());

        assertThat(response.resultPort()).isEqualTo("completed");
        ArgumentCaptor<ProviderChatRequest> routed =
                ArgumentCaptor.forClass(ProviderChatRequest.class);
        verify(fixture.gateway(), times(3)).chat(routed.capture());
        assertThat(routed.getAllValues().get(1).prompt())
                .contains("Your apply_patch call was refused: ")
                .contains("read_diff must establish the current diff digest first.");
    }

    /**
     * An empty diff has two very different causes and the person is told which. Job b4c9a477
     * looked and the change was already there; Job a15a51b1 tried eight patches, landed none,
     * and the screen still said the change was already there. The model reaching for an edit
     * is what separates them.
     */
    @Test
    void anEmptyDiffAfterAFailedEditIsNotCalledAlreadyDone() {
        ObjectMapper mapper = new ObjectMapper();
        StageFixture fixture = stageFixture(mapper);
        // Every patch is refused, so nothing lands and the diff stays empty.
        when(fixture.toolService().submitForNode(
                eq("Bearer worker"), any(), eq("code")))
                .thenThrow(new CodingToolException(
                        "TOOL_EXECUTION_FAILED",
                        "The MCP coding tool refused the call. git: error: patch failed",
                        org.springframework.http.HttpStatus.BAD_GATEWAY))
                .thenAnswer(acceptedSubmit(fixture.submittedToolCall()));
        when(fixture.gateway().chat(any())).thenReturn(
                toolCallReply("apply_patch", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                        "{\"patch\":\"diff\"}"),
                toolCallReply("read_diff", "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", "{}"),
                terminalReply());
        when(fixture.toolService().result("Bearer worker", EXECUTION)).thenAnswer(ignored ->
                new CodingToolContract.ResultContent(
                        "1.0",
                        UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
                        fixture.submittedToolCall().get(),
                        JOB, TRACE, "stage-tool.result", EXECUTION,
                        "application/json", 120,
                        "sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
                        "{\"workspaceId\":\"" + WORKSPACE + "\","
                                + "\"baseSha\":\"" + BASE_SHA + "\","
                                + "\"candidateSha\":\"" + BASE_SHA + "\","
                                + "\"digest\":\"sha256:e3b0c44298fc1c149afbf4c8996fb92427"
                                + "ae41e4649b934ca495991b7852b855\","
                                + "\"changedPaths\":[]}"));

        assertThatThrownBy(() -> fixture.service().execute(
                "Bearer worker", JOB, 1, RESULT, fixture.request()))
                .isInstanceOf(CodingWorkerException.class)
                .satisfies(failure -> assertThat(((CodingWorkerException) failure).code())
                        .isEqualTo("CODING_PATCH_NOT_APPLIED"))
                .hasMessageContaining("attempted an edit but no change was applied");
    }

    /**
     * Job cb3cd98b applied a working patch and then kept editing - thirteen apply_patch calls,
     * one of which reverted its own work - and burned the whole turn budget without ever
     * reaching the checks. Nothing told it the edit had landed or what came next. The same
     * request had finished in two patches the day before, so the nudge names the next step
     * rather than forbidding a second patch a two-part change still needs.
     */
    @Test
    void aSucceededPatchIsToldTheEditLandedAndWhatComesNext() {
        ObjectMapper mapper = new ObjectMapper();
        StageFixture fixture = stageFixture(mapper);
        when(fixture.toolService().submitForNode(
                eq("Bearer worker"), any(), eq("code")))
                .thenAnswer(acceptedSubmit(fixture.submittedToolCall()));
        when(fixture.gateway().chat(any())).thenReturn(
                toolCallReply("apply_patch", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                        "{\"patch\":\"diff\"}"),
                toolCallReply("read_diff", "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", "{}"),
                terminalReply());

        CodingHandlerContract.StageExecutionResponse response = fixture.service().execute(
                "Bearer worker", JOB, 1, RESULT, fixture.request());

        assertThat(response.resultPort()).isEqualTo("completed");
        ArgumentCaptor<ProviderChatRequest> routed =
                ArgumentCaptor.forClass(ProviderChatRequest.class);
        verify(fixture.gateway(), times(3)).chat(routed.capture());
        // The exit hands over instead of verifying: review and the deterministic preview
        // run the same checks, and this stage's conversation is the most expensive place
        // to run them (66k vs 8k tokens, Jobs 60401f37 / a8d8005a).
        // Files open one at a time: an unused read is re-sent with every later answer
        // (5,827 tokens x 8 answers on Job 60401f37), so the stage is told to open the
        // first targetFile and reach for the next only when the change does not fit.
        // Reads already known to be needed are grouped instead: told only that grouping
        // was allowed, haiku grouped one answer of twelve (Job 3d4b364e).
        assertThat(routed.getAllValues().get(0).prompt())
                .contains("start with the first targetFile")
                .contains("read_file only that range")
                .contains("Open a later")
                .contains("each extra reading answer is paid for again and again")
                .contains("you already know you will need")
                .contains("request one range that covers them")
                .contains("ranges of it you already know you need may be requested together");
        assertThat(routed.getAllValues().get(1).prompt())
                .contains("apply_patch succeeded")
                .contains("stop editing and finish with the stage result")
                .contains("review stage receives your work")
                .doesNotContain("call run_check")
                .contains("do not re-edit work that is already correct");
        // One patch landed, so the nudge is said once. read_diff is not an edit and adds none.
        assertThat(routed.getAllValues().get(2).prompt().split("apply_patch succeeded", -1))
                .hasSize(2);
    }

    /*
     * A model may hand back several tool calls in one answer; the loop executes the first
     * alone. Replaying the whole batch left the next turn declaring calls that had no result,
     * and the message contract refused the request - the job died as CONTRACT_VALIDATION_FAILED
     * with no file changed. The history must record what ran, not what was asked.
     */
    @Test
    void aParallelToolBatchReplaysOnlyTheExecutedCall() {
        ObjectMapper mapper = new ObjectMapper();
        StageFixture fixture = stageFixture(mapper);
        when(fixture.toolService().submitForNode(
                eq("Bearer worker"), any(), eq("code")))
                .thenAnswer(acceptedSubmit(fixture.submittedToolCall()));
        when(fixture.gateway().chat(any())).thenReturn(
                new ProviderChatResponse(
                        ModelProvider.GOOGLE_GENAI,
                        "coding-test-model",
                        "",
                        List.of(
                                new ProviderChatMessage.ToolCall(
                                        "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "read_diff", "{}"),
                                new ProviderChatMessage.ToolCall(
                                        "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", "read_diff", "{}"),
                                new ProviderChatMessage.ToolCall(
                                        "cccccccc-cccc-4ccc-8ccc-cccccccccccc", "read_diff", "{}")),
                        10, 5, Duration.ofMillis(10)),
                terminalReply());

        CodingHandlerContract.StageExecutionResponse response = fixture.service().execute(
                "Bearer worker", JOB, 1, RESULT, fixture.request());

        assertThat(response.resultPort()).isEqualTo("completed");
        ArgumentCaptor<ProviderChatRequest> routed =
                ArgumentCaptor.forClass(ProviderChatRequest.class);
        verify(fixture.gateway(), times(2)).chat(routed.capture());
        List<ProviderChatMessage> replayed = routed.getAllValues().get(1).messages();
        ProviderChatMessage assistant = replayed.stream()
                .filter(message -> message.role() == ProviderChatMessage.Role.ASSISTANT)
                .findFirst().orElseThrow();
        assertThat(assistant.toolCalls()).hasSize(1);
        assertThat(assistant.toolCalls().get(0).id())
                .isEqualTo("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    }

    private static final String DIFF_CALL = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
    private static final String READ_B = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
    private static final String READ_C = "cccccccc-cccc-4ccc-8ccc-cccccccccccc";
    private static final String READ_D = "dddddddd-dddd-4ddd-8ddd-dddddddddddd";
    private static final String READ_E = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee";
    private static final String READ_F = "ffffffff-ffff-4fff-8fff-ffffffffffff";
    private static final String READ_G = "10101010-1010-4101-8101-101010101010";
    private static final String READ_H = "20202020-2020-4202-8202-202020202020";
    private static final String READ_I = "30303030-3030-4303-8303-303030303030";
    private static final String READ_J = "40404040-4040-4404-8404-404040404040";
    /** The call id of the read_diff the stage runs itself before the first answer. */
    private static final String PRE_EDIT_DIFF_CALL = UUID.nameUUIDFromBytes(
            (RESULT + ":attempt:1:tool:1099:read_diff")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();

    /** One answer carrying several reads, the way Claude sends them when it may. */
    private static ProviderChatResponse readsReply(ModelProvider provider, String... callIds) {
        List<ProviderChatMessage.ToolCall> calls = new java.util.ArrayList<>();
        for (int index = 0; index < callIds.length; index++) {
            calls.add(new ProviderChatMessage.ToolCall(callIds[index], "read_file",
                    "{\"path\":\"src/App.java\",\"startLine\":" + (index * 10 + 1)
                            + ",\"endLine\":" + (index * 10 + 9) + "}"));
        }
        return new ProviderChatResponse(provider, "coding-test-model", "",
                List.copyOf(calls), 10, 5, Duration.ofMillis(10));
    }

    /** The usual accepted submit, also recording which call ids reached the tool service. */
    private static org.mockito.stubbing.Answer<CodingToolContract.Accepted> recordingSubmit(
            StageFixture fixture, List<String> submitted) {
        org.mockito.stubbing.Answer<CodingToolContract.Accepted> accept =
                acceptedSubmit(fixture.submittedToolCall());
        return invocation -> {
            submitted.add(((JsonNode) invocation.getArgument(1)).path("toolCallId").asText());
            return accept.answer(invocation);
        };
    }

    private static ProviderChatMessage lastAssistant(ProviderChatRequest request) {
        List<ProviderChatMessage> assistants = request.messages().stream()
                .filter(message -> message.role() == ProviderChatMessage.Role.ASSISTANT)
                .toList();
        return assistants.get(assistants.size() - 1);
    }

    private static List<String> toolResultIds(ProviderChatRequest request) {
        return request.messages().stream()
                .filter(message -> message.role() == ProviderChatMessage.Role.TOOL)
                .map(ProviderChatMessage::toolCallId)
                .toList();
    }

    /*
     * AI04-032: every answer re-sends the whole conversation, so reads that do not wait on
     * each other run together from one answer - replayed on Jobs bff4fd0b, 6c75d4ce, 375651f7
     * and 99748158 that removes 28-55% of the code stage's input tokens. All of them run, in
     * the order given, and the next request records the calls followed by their results.
     */
    @Test
    void readsFromOneAnswerRunTogetherAndReplayInOrder() {
        ObjectMapper mapper = new ObjectMapper();
        StageFixture fixture = stageFixture(mapper);
        List<String> submitted = new java.util.ArrayList<>();
        when(fixture.toolService().submitForNode(eq("Bearer worker"), any(), eq("code")))
                .thenAnswer(recordingSubmit(fixture, submitted));
        when(fixture.gateway().chat(any())).thenReturn(
                toolCallReply("read_diff", DIFF_CALL, "{}"),
                readsReply(ModelProvider.GOOGLE_GENAI, READ_B, READ_C, READ_D),
                terminalReply());

        CodingHandlerContract.StageExecutionResponse response = fixture.service().execute(
                "Bearer worker", JOB, 1, RESULT, fixture.request());

        assertThat(response.resultPort()).isEqualTo("completed");
        assertThat(submitted).containsExactly(
                PRE_EDIT_DIFF_CALL, DIFF_CALL, READ_B, READ_C, READ_D);
        ArgumentCaptor<ProviderChatRequest> routed =
                ArgumentCaptor.forClass(ProviderChatRequest.class);
        verify(fixture.gateway(), times(3)).chat(routed.capture());
        ProviderChatRequest third = routed.getAllValues().get(2);
        assertThat(lastAssistant(third).toolCalls())
                .extracting(ProviderChatMessage.ToolCall::id)
                .containsExactly(READ_B, READ_C, READ_D);
        assertThat(toolResultIds(third)).containsExactly(DIFF_CALL, READ_B, READ_C, READ_D);
    }

    /*
     * A refusal inside a group stops the group there. The read before it ran and is recorded
     * with its result; the refused read and the one after it never ran, so they are left out
     * of the history - a tool result needs a real execution behind it - and the refusal
     * reason is handed back as for a single call. Leaving them out keeps the ran calls as a
     * leading run, at the positions their Gemini thought signatures were issued for.
     */
    @Test
    void aRefusalInsideAGroupKeepsOnlyTheReadsThatRan() {
        ObjectMapper mapper = new ObjectMapper();
        StageFixture fixture = stageFixture(mapper);
        List<String> submitted = new java.util.ArrayList<>();
        when(fixture.toolService().submitForNode(eq("Bearer worker"), any(), eq("code")))
                .thenAnswer(recordingSubmit(fixture, submitted))
                .thenAnswer(recordingSubmit(fixture, submitted))
                .thenAnswer(recordingSubmit(fixture, submitted))
                .thenThrow(new CodingToolException(
                        "TOOL_ARGUMENTS_INVALID", "read_file range is invalid.",
                        org.springframework.http.HttpStatus.BAD_REQUEST))
                .thenAnswer(recordingSubmit(fixture, submitted));
        when(fixture.gateway().chat(any())).thenReturn(
                toolCallReply("read_diff", DIFF_CALL, "{}"),
                readsReply(ModelProvider.GOOGLE_GENAI, READ_B, READ_C, READ_D),
                terminalReply());

        CodingHandlerContract.StageExecutionResponse response = fixture.service().execute(
                "Bearer worker", JOB, 1, RESULT, fixture.request());

        assertThat(response.resultPort()).isEqualTo("completed");
        assertThat(submitted).containsExactly(PRE_EDIT_DIFF_CALL, DIFF_CALL, READ_B);
        verify(fixture.toolService(), times(4))
                .submitForNode(eq("Bearer worker"), any(), eq("code"));
        ArgumentCaptor<ProviderChatRequest> routed =
                ArgumentCaptor.forClass(ProviderChatRequest.class);
        verify(fixture.gateway(), times(3)).chat(routed.capture());
        ProviderChatRequest third = routed.getAllValues().get(2);
        assertThat(lastAssistant(third).toolCalls())
                .extracting(ProviderChatMessage.ToolCall::id)
                .containsExactly(READ_B);
        assertThat(toolResultIds(third)).containsExactly(DIFF_CALL, READ_B);
        List<ProviderChatMessage> messages = third.messages();
        ProviderChatMessage last = messages.get(messages.size() - 1);
        assertThat(last.role()).isEqualTo(ProviderChatMessage.Role.USER);
        assertThat(last.content())
                .contains("Your read_file call was refused: read_file range is invalid.");
    }

    /*
     * Grouped reads put several results after one assistant message. The fold used to name a
     * result by the message right before it, which for the second read of a group is the
     * first read's result - so a group's later reads never folded. The call is now matched by
     * its id: with a fold depth of one, once a later answer reads, both reads of the older
     * group fold and the newest read stays.
     */
    @Test
    void foldsEveryReadOfAGroupNotOnlyItsFirst() {
        ObjectMapper mapper = new ObjectMapper();
        StageFixture fixture = stageFixture(
                mapper, bindingPolicy(mapper), 1, ModelProvider.ANTHROPIC);
        when(fixture.toolService().submitForNode(eq("Bearer worker"), any(), eq("code")))
                .thenAnswer(acceptedSubmit(fixture.submittedToolCall()));
        when(fixture.gateway().chat(any())).thenReturn(
                toolCallReply(ModelProvider.ANTHROPIC, "read_diff", DIFF_CALL, "{}"),
                readsReply(ModelProvider.ANTHROPIC, READ_B, READ_C),
                readsReply(ModelProvider.ANTHROPIC, READ_D),
                terminalReply(ModelProvider.ANTHROPIC));

        CodingHandlerContract.StageExecutionResponse response = fixture.service().execute(
                "Bearer worker", JOB, 1, RESULT, fixture.request());

        assertThat(response.resultPort()).isEqualTo("completed");
        ArgumentCaptor<ProviderChatRequest> routed =
                ArgumentCaptor.forClass(ProviderChatRequest.class);
        verify(fixture.gateway(), times(4)).chat(routed.capture());
        // Third request: the group is the only answer that read, so both bodies stay.
        List<String> third = toolBodies(routed.getAllValues().get(2));
        assertThat(third).hasSize(3);
        assertThat(third).allSatisfy(body -> assertThat(body).contains(DIFF_DIGEST));
        // Fourth request: a later answer read, so the whole older group folds.
        List<String> fourth = toolBodies(routed.getAllValues().get(3));
        assertThat(fourth).hasSize(4);
        assertThat(fourth.get(0)).contains(DIFF_DIGEST);
        assertThat(fourth.get(1)).contains("folded").doesNotContain(DIFF_DIGEST);
        assertThat(fourth.get(2)).contains("folded").doesNotContain(DIFF_DIGEST);
        assertThat(fourth.get(3)).contains(DIFF_DIGEST);
    }

    /*
     * AI04-034: the fold depth counts answers, not results. One answer that grouped three
     * reads used to fill the depth of three at once, and from the next answer on every new
     * read folded a body the model was still working from - on Job 543eb70f all six re-reads
     * of the code stage came right after the fold of the range they re-read. A group of three
     * plus two more reads now stays whole through the third answer, and the group folds as
     * one when a fourth answer reads.
     */
    @Test
    void aGroupOfReadsCountsAsOneAnswerOfTheFoldDepth() {
        ObjectMapper mapper = new ObjectMapper();
        StageFixture fixture = stageFixture(
                mapper, bindingPolicy(mapper), 3, ModelProvider.ANTHROPIC);
        when(fixture.toolService().submitForNode(eq("Bearer worker"), any(), eq("code")))
                .thenAnswer(acceptedSubmit(fixture.submittedToolCall()));
        when(fixture.gateway().chat(any())).thenReturn(
                readsReply(ModelProvider.ANTHROPIC, READ_B, READ_C, READ_D),
                readsReply(ModelProvider.ANTHROPIC, READ_E),
                readsReply(ModelProvider.ANTHROPIC, READ_F),
                readsReply(ModelProvider.ANTHROPIC, READ_G),
                terminalReply(ModelProvider.ANTHROPIC));

        CodingHandlerContract.StageExecutionResponse response = fixture.service().execute(
                "Bearer worker", JOB, 1, RESULT, fixture.request());

        assertThat(response.resultPort()).isEqualTo("completed");
        ArgumentCaptor<ProviderChatRequest> routed =
                ArgumentCaptor.forClass(ProviderChatRequest.class);
        verify(fixture.gateway(), times(5)).chat(routed.capture());
        // Fourth request: three answers read (the group, E, F) - nothing folds yet.
        List<String> fourth = toolBodies(routed.getAllValues().get(3));
        assertThat(fourth).hasSize(5);
        assertThat(fourth).allSatisfy(
                body -> assertThat(body).contains(DIFF_DIGEST).doesNotContain("folded"));
        // Fifth request: a fourth answer read, so the group folds as one.
        List<String> fifth = toolBodies(routed.getAllValues().get(4));
        assertThat(fifth).hasSize(6);
        assertThat(fifth.subList(0, 3)).allSatisfy(
                body -> assertThat(body).contains("folded").doesNotContain(DIFF_DIGEST));
        assertThat(fifth.subList(3, 6)).allSatisfy(
                body -> assertThat(body).contains(DIFF_DIGEST).doesNotContain("folded"));
    }

    /* Three grouped answers could hold nine bodies; the cap keeps six and folds the oldest group. */
    @Test
    void keptBodiesAreCappedAtSixAcrossGroupedAnswers() {
        ObjectMapper mapper = new ObjectMapper();
        StageFixture fixture = stageFixture(
                mapper, bindingPolicy(mapper), 3, ModelProvider.ANTHROPIC);
        when(fixture.toolService().submitForNode(eq("Bearer worker"), any(), eq("code")))
                .thenAnswer(acceptedSubmit(fixture.submittedToolCall()));
        when(fixture.gateway().chat(any())).thenReturn(
                readsReply(ModelProvider.ANTHROPIC, READ_B, READ_C, READ_D),
                readsReply(ModelProvider.ANTHROPIC, READ_E, READ_F, READ_G),
                readsReply(ModelProvider.ANTHROPIC, READ_H, READ_I, READ_J),
                terminalReply(ModelProvider.ANTHROPIC));

        CodingHandlerContract.StageExecutionResponse response = fixture.service().execute(
                "Bearer worker", JOB, 1, RESULT, fixture.request());

        assertThat(response.resultPort()).isEqualTo("completed");
        ArgumentCaptor<ProviderChatRequest> routed =
                ArgumentCaptor.forClass(ProviderChatRequest.class);
        verify(fixture.gateway(), times(4)).chat(routed.capture());
        List<String> fourth = toolBodies(routed.getAllValues().get(3));
        assertThat(fourth).hasSize(9);
        assertThat(fourth.subList(0, 3)).allSatisfy(
                body -> assertThat(body).contains("folded").doesNotContain(DIFF_DIGEST));
        assertThat(fourth.subList(3, 9)).allSatisfy(
                body -> assertThat(body).contains(DIFF_DIGEST).doesNotContain("folded"));
    }

    /*
     * AI04-034: apply_patch refuses an edit until a read_diff has established the diff
     * digest, and every measured Job spent its first answer on that read_diff. The stage now
     * runs it before the model's first answer under its own call id, keeps its body out of
     * the conversation, and tells the model the first answer can already be the edit.
     */
    @Test
    void theCodeStageEstablishesTheDiffBeforeTheModelsFirstAnswer() {
        ObjectMapper mapper = new ObjectMapper();
        StageFixture fixture = stageFixture(mapper);
        List<JsonNode> submitted = new ArrayList<>();
        when(fixture.toolService().submitForNode(eq("Bearer worker"), any(), eq("code")))
                .thenAnswer(invocation -> {
                    submitted.add(invocation.getArgument(1));
                    return acceptedSubmit(fixture.submittedToolCall()).answer(invocation);
                });
        when(fixture.gateway().chat(any())).thenReturn(
                toolCallReply("apply_patch", READ_B, "{\"patch\":\"diff\"}"),
                terminalReply());

        CodingHandlerContract.StageExecutionResponse response = fixture.service().execute(
                "Bearer worker", JOB, 1, RESULT, fixture.request());

        assertThat(response.resultPort()).isEqualTo("completed");
        assertThat(response.diffDigest()).isEqualTo(DIFF_DIGEST);
        assertThat(submitted)
                .extracting(toolRequest -> toolRequest.path("tool").path("name").asText())
                .containsExactly("read_diff", "apply_patch");
        assertThat(submitted.get(0).path("toolCallId").asText()).isEqualTo(PRE_EDIT_DIFF_CALL);
        ArgumentCaptor<ProviderChatRequest> routed =
                ArgumentCaptor.forClass(ProviderChatRequest.class);
        verify(fixture.gateway(), times(2)).chat(routed.capture());
        assertThat(routed.getAllValues().get(0).messages().get(0).content())
                .contains("The current diff is already established for you")
                .doesNotContain("Use read_diff once before the first apply_patch");
        assertThat(toolBodies(routed.getAllValues().get(0))).isEmpty();
    }

    /* With the diff established by the stage, an answer that never edits still ends with one. */
    @Test
    void aStageThatNeverEditsEndsWithTheDiffTheStageEstablished() {
        ObjectMapper mapper = new ObjectMapper();
        StageFixture fixture = stageFixture(mapper);
        when(fixture.toolService().submitForNode(eq("Bearer worker"), any(), eq("code")))
                .thenAnswer(acceptedSubmit(fixture.submittedToolCall()));
        when(fixture.gateway().chat(any())).thenReturn(terminalReply());

        CodingHandlerContract.StageExecutionResponse response = fixture.service().execute(
                "Bearer worker", JOB, 1, RESULT, fixture.request());

        assertThat(response.resultPort()).isEqualTo("completed");
        assertThat(response.diffDigest()).isEqualTo(DIFF_DIGEST);
        verify(fixture.toolService(), times(1))
                .submitForNode(eq("Bearer worker"), any(), eq("code"));
        verify(fixture.gateway(), times(1)).chat(any());
    }

    /* A refused stage read_diff is left to the model, which the tool's safety net still asks for. */
    @Test
    void aRefusedStageReadDiffLeavesTheDiffToTheModel() {
        ObjectMapper mapper = new ObjectMapper();
        StageFixture fixture = stageFixture(mapper);
        when(fixture.toolService().submitForNode(eq("Bearer worker"), any(), eq("code")))
                .thenThrow(new CodingToolException(
                        "TOOL_EXECUTION_FAILED", "The MCP coding tool refused the call.",
                        org.springframework.http.HttpStatus.BAD_GATEWAY))
                .thenAnswer(acceptedSubmit(fixture.submittedToolCall()));
        when(fixture.gateway().chat(any())).thenReturn(
                toolCallReply("read_diff", DIFF_CALL, "{}"),
                terminalReply());

        CodingHandlerContract.StageExecutionResponse response = fixture.service().execute(
                "Bearer worker", JOB, 1, RESULT, fixture.request());

        assertThat(response.resultPort()).isEqualTo("completed");
        assertThat(response.diffDigest()).isEqualTo(DIFF_DIGEST);
        verify(fixture.toolService(), times(2))
                .submitForNode(eq("Bearer worker"), any(), eq("code"));
    }

    /*
     * Which calls of one answer run. Only reads group, only in the code stage, and at most
     * three: an edit goes alone because the order of edits and reads matters, and the review
     * stage keeps judging one call at a time.
     */
    @Test
    void onlyAnAnswerOfReadsInTheCodeStageRunsMoreThanItsFirstCall() {
        JsonNode none = new ObjectMapper().createObjectNode();
        org.urizo.axmodulestudio.backend.coding.dto.CodingModelTurnContract.ToolCall first =
                new org.urizo.axmodulestudio.backend.coding.dto.CodingModelTurnContract.ToolCall(
                        UUID.fromString(READ_B), "read_file", none);
        org.urizo.axmodulestudio.backend.coding.dto.CodingModelTurnContract.ToolCall second =
                new org.urizo.axmodulestudio.backend.coding.dto.CodingModelTurnContract.ToolCall(
                        UUID.fromString(READ_C), "search_code", none);
        org.urizo.axmodulestudio.backend.coding.dto.CodingModelTurnContract.ToolCall third =
                new org.urizo.axmodulestudio.backend.coding.dto.CodingModelTurnContract.ToolCall(
                        UUID.fromString(READ_D), "read_file", none);
        org.urizo.axmodulestudio.backend.coding.dto.CodingModelTurnContract.ToolCall fourth =
                new org.urizo.axmodulestudio.backend.coding.dto.CodingModelTurnContract.ToolCall(
                        UUID.fromString(DIFF_CALL), "read_file", none);
        org.urizo.axmodulestudio.backend.coding.dto.CodingModelTurnContract.ToolCall patch =
                new org.urizo.axmodulestudio.backend.coding.dto.CodingModelTurnContract.ToolCall(
                        UUID.fromString(EXECUTION.toString()), "apply_patch", none);

        assertThat(CodingHandlerStageService.executableBatch(
                "coding.code", List.of(first, second, third, fourth)))
                .containsExactly(first, second, third);
        assertThat(CodingHandlerStageService.executableBatch(
                "coding.code", List.of(first, patch, second)))
                .containsExactly(first);
        assertThat(CodingHandlerStageService.executableBatch(
                "coding.code", List.of(patch, first)))
                .containsExactly(patch);
        assertThat(CodingHandlerStageService.executableBatch(
                "coding.review", List.of(first, second)))
                .containsExactly(first);
        assertThat(CodingHandlerStageService.executableBatch(
                "coding.code", List.of(first)))
                .containsExactly(first);
    }

    @Test
    void anEmptySnapshotAndStageToolIntersectionExposesNoTools() {
        assertThat(CodingHandlerStageService.allowedTools(
                Set.of("apply_patch"), Set.of("read_diff"))).isEmpty();
    }

    @Test
    void boundCodingPolicyDecodeFailsClosedForMissingOrUnknownTools() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        CodingToolService.RuntimePolicy valid = CodingToolService.decodeRuntimePolicy(
                mapper,
                "{\"toolPolicy\":{\"allowedTools\":[\"read_diff\"]},"
                        + "\"guardrailProfileKey\":\"central.default\"}");
        assertThat(valid.allowedTools()).containsExactly("read_diff");
        assertThat(valid.toolBindings().legacy()).isTrue();

        CodingToolService.RuntimePolicy bound = CodingToolService.decodeRuntimePolicy(
                mapper, Files.readString(Path.of(
                        "contracts/fixtures/orchestration/llm-ops-coding-handler.snapshot.valid.json")));
        assertThat(bound.toolBindings().legacy()).isFalse();
        assertThat(bound.toolBindings().modelToolsForNode("review"))
                .doesNotContain("apply_patch");

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
    void nodeBindingsSeparateModelSchemasFromDeterministicPreviewTools() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ProfileToolBindingPolicy bindings = bindingPolicy(mapper);
        CodingToolService.StageAuthority authority = new CodingToolService.StageAuthority(
                TRACE, 4,
                UUID.fromString("11111111-1111-4111-8111-111111111111"),
                UUID.fromString("22222222-2222-4222-8222-222222222222"),
                UUID.fromString("33333333-3333-4333-8333-333333333333"),
                UUID.fromString("44444444-4444-4444-8444-444444444444"),
                "coding", BASE_SHA,
                "sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",
                "sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
                "coding-v1", Set.of("CHAT", "TOOL_CALLING"), Set.of("coding"),
                Set.copyOf(CodingToolService.CODING_TOOL_SCHEMA_DIGESTS.keySet()),
                bindings, NOW.plusSeconds(60), PROFILE);
        Set<String> reviewTools = Set.of(
                "read_file", "search_code", "read_diff", "run_check",
                "check_package_allowlist", "scan_changed_files");

        assertThat(CodingHandlerStageService.modelTools(
                authority, "code", CodingToolService.CODING_TOOL_SCHEMA_DIGESTS.keySet()))
                .containsExactlyInAnyOrderElementsOf(
                        CodingToolService.CODING_TOOL_SCHEMA_DIGESTS.keySet());
        assertThat(CodingHandlerStageService.modelTools(authority, "review", reviewTools))
                .containsExactlyInAnyOrderElementsOf(reviewTools)
                .doesNotContain("apply_patch");
        assertThat(bindings.modelToolsForNode("preview")).isEmpty();
        assertThat(bindings.systemToolsForNode("preview")).containsExactlyInAnyOrder(
                "read_diff", "run_check", "check_package_allowlist", "scan_changed_files");
    }

    @Test
    void optionalCodeBindingSubsetControlsModelSchemaAndFinalToolAuthority()
            throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode snapshot = (ObjectNode) mapper.readTree(Files.readString(Path.of(
                "contracts/fixtures/orchestration/llm-ops-coding-handler.snapshot.valid.json")));
        snapshot.withObject("toolBindings").withObject("code").remove("apply_patch");
        ProfileToolBindingPolicy subset = ProfileToolBindingPolicy.decode(
                snapshot, CodingToolService.CODING_TOOL_SCHEMA_DIGESTS.keySet());
        StageFixture fixture = stageFixture(mapper, subset);
        when(fixture.toolService().submitForNode(
                eq("Bearer worker"), any(), eq("code")))
                .thenAnswer(acceptedSubmit(fixture.submittedToolCall()));
        when(fixture.gateway().chat(any())).thenReturn(
                toolCallReply("read_diff", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "{}"),
                terminalReply());

        fixture.service().execute("Bearer worker", JOB, 1, RESULT, fixture.request());

        ArgumentCaptor<ProviderChatRequest> request =
                ArgumentCaptor.forClass(ProviderChatRequest.class);
        verify(fixture.gateway(), times(2)).chat(request.capture());
        assertThat(request.getAllValues().get(0).tools())
                .extracting(tool -> tool.name())
                .contains("read_file")
                .doesNotContain("apply_patch");
        assertThatThrownBy(() -> CodingToolService.requireNodeToolAllowed(
                subset, "code", "apply_patch"))
                .isInstanceOfSatisfying(CodingToolException.class,
                        failure -> assertThat(failure.code()).isEqualTo("TOOL_NOT_ALLOWED"));
        assertThatCode(() -> CodingToolService.requireNodeToolAllowed(
                subset, "code", "read_file")).doesNotThrowAnyException();
        assertThatThrownBy(() -> CodingToolService.requireNodeToolAllowed(
                subset, "review", "apply_patch"))
                .isInstanceOfSatisfying(CodingToolException.class,
                        failure -> assertThat(failure.code()).isEqualTo("TOOL_NOT_ALLOWED"));
        assertThatCode(() -> CodingToolService.requireNodeToolAllowed(
                bindingPolicy(mapper), "code", "apply_patch"))
                .doesNotThrowAnyException();
        assertThatCode(() -> CodingToolService.requireNodeToolAllowed(
                ProfileToolBindingPolicy.legacy(Set.of("apply_patch")),
                "unbound_legacy_node", "apply_patch"))
                .doesNotThrowAnyException();
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
                Set.of(
                        ModelCapability.CHAT,
                        ModelCapability.TOOL_CALLING,
                        ModelCapability.STRUCTURED_OUTPUT),
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
        ProfileModelBindingService profileModelBindings =
                mock(ProfileModelBindingService.class);
        when(profileModelBindings.resolve(
                PROFILE, "analyze", "coding.analyze", ModelUseCase.STRUCTURED_OUTPUT))
                .thenReturn(List.of(registration));
        // The analyst is shown the fence so it can refuse an out-of-fence request before the
        // coding stage spends anything. The snapshot is the job's own copy.
        GuardrailPathSelectionService selections = mock(GuardrailPathSelectionService.class);
        when(selections.jobSnapshot(JOB)).thenReturn(List.of(
                "backend:src/main/java/org/urizo/axmodulestudio/backend/cms"));
        when(selections.jobAreas(JOB)).thenReturn(
                new GuardrailPathSelectionService.JobAreas(
                        List.of("CMS 기능"), List.of("상태 점검", "공통 기반")));
        // The fence's own file list: the analyst picks targetFiles from it instead of
        // leaving the coding stage to find the files by searching.
        when(selections.jobFiles(JOB)).thenReturn(List.of(
                "src/main/java/org/urizo/axmodulestudio/backend/cms/dto/CmsResponses.java",
                "src/main/java/org/urizo/axmodulestudio/backend/cms/repository/CmsRepository.java"));
        CodingHandlerStageService service = new CodingHandlerStageService(
                resultService, toolService, guard, modelService,
                mock(CodingRunnerService.class), mock(DeploymentAdapter.class), profileModelBindings,
                selections,
                mock(GuardrailRuleService.class), mapper, clock);
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
                NOW.plusSeconds(60),
                PROFILE);
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
        String stageResult = "{\"port\":\"feasible\",\"payload\":{\"planSummary\":\"버튼을 잠급니다.\","
                + "\"acceptanceCriteria\":[\"이유가 보인다\"],"
                + "\"targetFiles\":[\"src/main/java/org/urizo/axmodulestudio/backend/cms/"
                + "dto/CmsResponses.java\"]}}";
        when(gateway.chat(any())).thenReturn(
                assistantText("```json\n" + stageResult + "\n```"));

        CodingHandlerContract.StageExecutionResponse response = service.execute(
                "Bearer worker", JOB, 1, RESULT,
                new CodingHandlerContract.StageExecutionRequest(
                        "1.0", TRACE, 4, 1, "coding.analyze", RESULT));

        assertThat(response.resultPort()).isEqualTo("feasible");
        ObjectNode expectedPayload = mapper.createObjectNode()
                .put("planSummary", "버튼을 잠급니다.");
        expectedPayload.putArray("acceptanceCriteria").add("이유가 보인다");
        expectedPayload.putArray("targetFiles")
                .add("src/main/java/org/urizo/axmodulestudio/backend/cms/dto/CmsResponses.java");
        assertThat(response.payload()).isEqualTo(expectedPayload);
        verify(profileModelBindings).resolve(
                PROFILE, "analyze", "coding.analyze", ModelUseCase.STRUCTURED_OUTPUT);
        ArgumentCaptor<CodingModelTurnContract.Request> structuredRequest =
                ArgumentCaptor.forClass(CodingModelTurnContract.Request.class);
        verify(guard).reserve(eq("Bearer worker"), structuredRequest.capture());
        assertThat(structuredRequest.getValue().responseFormat().path("type").asText())
                .isEqualTo("JSON_SCHEMA");
        assertThat(structuredRequest.getValue().responseFormat()
                .path("outputSchema").path("required"))
                .extracting(JsonNode::asText)
                .containsExactly("port", "payload");
        JsonNode payloadSchema = structuredRequest.getValue().responseFormat()
                .path("outputSchema").path("properties").path("payload");
        assertThat(payloadSchema.path("additionalProperties").asBoolean()).isFalse();
        assertThat(payloadSchema.path("required"))
                .extracting(JsonNode::asText)
                .containsExactly("planSummary", "acceptanceCriteria", "targetFiles");
        JsonNode payloadProperties = payloadSchema.path("properties");
        assertThat(payloadProperties.path("planSummary").path("type").asText())
                .isEqualTo("string");
        // The criteria are a list, so the schema has to declare the element type as well.
        assertThat(payloadProperties.path("acceptanceCriteria").path("type").asText())
                .isEqualTo("array");
        assertThat(payloadProperties.path("acceptanceCriteria").path("items").path("type").asText())
                .isEqualTo("string");

        // The design's early block: the analyst sees the fence as labels and is told to answer
        // infeasible when the request clearly needs work outside it. The denied side is shown
        // too — without it the analyst hoped out-of-fence files lived inside an allowed folder.
        // The post-check on the finished candidate stays the authority either way.
        String systemPrompt = structuredRequest.getValue().messages().get(0).path("content").asText();
        assertThat(systemPrompt).contains("guardrail.allowedAreas");
        assertThat(systemPrompt).contains("guardrail.deniedAreas");
        assertThat(systemPrompt).contains("\"infeasible\"");
        assertThat(systemPrompt).contains("super administrator");
        // Data-versus-code refusal: a stored menu name cannot be edited here (Job a40a115d
        // burned 326k tokens tracing one), while wording hard-coded in a screen file can.
        // The unsure case must stay feasible, or screen-wording requests start bouncing.
        assertThat(systemPrompt).contains("stored data rather than code");
        assertThat(systemPrompt).contains("CMS administration screens");
        assertThat(systemPrompt).contains("when genuinely unsure, proceed");
        String contextMessage = structuredRequest.getValue().messages().get(1).path("content").asText();
        assertThat(contextMessage).contains("CMS 기능");
        assertThat(contextMessage).contains("상태 점검");
        // The file list belongs in the analyst's context, and the instruction has to say
        // where targetFiles comes from or the model invents paths.
        assertThat(contextMessage).contains("CmsResponses.java");
        assertThat(systemPrompt).contains("targetFiles");
        assertThat(systemPrompt).contains("guardrail.files");
        // planSummary is read by a general administrator, so the analyst is never shown a path
        // it could quote back.
        assertThat(contextMessage)
                .doesNotContain("backend:src/main/java/org/urizo/axmodulestudio/backend/cms");

        // Prose alone carries no object to recover, so the stage still fails.
        when(gateway.chat(any())).thenReturn(assistantText("I could not decide."));

        assertThatThrownBy(() -> service.execute(
                "Bearer worker", JOB, 1, RESULT,
                new CodingHandlerContract.StageExecutionRequest(
                        "1.0", TRACE, 4, 1, "coding.analyze", RESULT)))
                .isInstanceOfSatisfying(ProviderGatewayException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(ModelGatewayErrorCode.MODEL_RESPONSE_INVALID));
    }

    /**
     * The analyst names files it was shown; a model that answers with something else must not
     * hand that on to the coding stage, which would spend a turn reading a path that is not
     * there. Dropped rather than refused - analyze is one call with no re-ask, so a refusal
     * would end the job at planning.
     */
    @Test
    void keepsOnlyTheTargetFilesTheFenceCanAccountFor() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AnalyzeFixture fixture = analyzeFixture(mapper);
        when(fixture.gateway().chat(any())).thenReturn(assistantText(
                "{\"port\":\"feasible\",\"payload\":{\"planSummary\":\"고칩니다.\","
                        + "\"acceptanceCriteria\":[\"보인다\"],\"targetFiles\":["
                        // Real, but written the way a model often writes it.
                        + "\"./src/main/java/org/urizo/axmodulestudio/backend/cms/dto/"
                        + "CmsResponses.java\","
                        // A file that does not exist anywhere.
                        + "\"src/main/java/org/urizo/axmodulestudio/backend/cms/"
                        + "MemberJoinDateView.java\","
                        // A real file, but outside the fence.
                        + "\"src/main/java/org/urizo/axmodulestudio/backend/auth/"
                        + "AuthService.java\"]}}"));

        CodingHandlerContract.StageExecutionResponse response = fixture.service().execute(
                "Bearer worker", JOB, 1, RESULT,
                new CodingHandlerContract.StageExecutionRequest(
                        "1.0", TRACE, 4, 1, "coding.analyze", RESULT));

        assertThat(response.payload().path("targetFiles"))
                .extracting(JsonNode::asText)
                .containsExactly(
                        // Repaired shape kept, invented path dropped, and the new file inside
                        // the fence kept: the post-check accepts a file the change creates.
                        "src/main/java/org/urizo/axmodulestudio/backend/cms/dto/CmsResponses.java",
                        "src/main/java/org/urizo/axmodulestudio/backend/cms/MemberJoinDateView.java");
    }

    @Test
    void idempotentModelTurnReplayUsesCachedResponseWithoutCallingProvider() {
        ObjectMapper mapper = new ObjectMapper();
        AnalyzeFixture fixture = analyzeFixture(mapper);
        ObjectNode payload = mapper.createObjectNode().put("planSummary", "고칩니다.");
        payload.putArray("acceptanceCriteria").add("완료한다");
        payload.putArray("targetFiles").add(
                "src/main/java/org/urizo/axmodulestudio/backend/cms/dto/CmsResponses.java");
        ObjectNode structured = mapper.createObjectNode().put("port", "feasible");
        structured.set("payload", payload);
        CodingModelTurnContract.Response cached = new CodingModelTurnContract.Response(
                "1.0",
                UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
                JOB,
                TRACE,
                "stage.cached-model-turn",
                new CodingModelTurnContract.Assistant("assistant", ""),
                List.of(),
                new CodingModelTurnContract.JsonSchemaResponseFormat(
                        "JSON_SCHEMA",
                        "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                        structured),
                new CodingModelTurnContract.SelectedModel("OPENAI", "gpt-test"),
                new CodingModelTurnContract.TokenUsage(12, 6, 18),
                10,
                "STOP",
                NOW);
        when(fixture.guard().reserve(eq("Bearer worker"), any())).thenAnswer(invocation -> {
            CodingModelTurnContract.Request request = invocation.getArgument(1);
            return CodingModelTurnPermit.replay(
                    request.jobId(), request.idempotencyKey(), cached);
        });

        CodingHandlerContract.StageExecutionResponse response = fixture.service().execute(
                "Bearer worker", JOB, 1, RESULT,
                new CodingHandlerContract.StageExecutionRequest(
                        "1.0", TRACE, 4, 1, "coding.analyze", RESULT));

        assertThat(response.resultPort()).isEqualTo("feasible");
        verify(fixture.gateway(), never()).chat(any());
    }

    /** Wiring for the single-call analyze stage; the gateway answer is per-test. */
    private record AnalyzeFixture(
            CodingHandlerStageService service,
            ProviderChatGatewayPort gateway,
            CodingModelTurnGuard guard,
            GuardrailPathSelectionService selections) { }

    /**
     * The fence a job was created under: one allowed folder, and the two files the scan found
     * inside it. Enough for the analyst to choose from and for the server to check the choice.
     */
    private static AnalyzeFixture analyzeFixture(ObjectMapper mapper) {
        CodingHandlerResultService resultService = mock(CodingHandlerResultService.class);
        CodingToolService toolService = mock(CodingToolService.class);
        CodingModelTurnGuard guard = mock(CodingModelTurnGuard.class);
        ProviderChatGatewayPort gateway = mock(ProviderChatGatewayPort.class);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        ProviderModelRegistration registration = new ProviderModelRegistration(
                ModelProvider.GOOGLE_GENAI,
                "coding-test-model",
                Set.of(ModelCapability.CHAT, ModelCapability.TOOL_CALLING,
                        ModelCapability.STRUCTURED_OUTPUT),
                Duration.ofSeconds(30),
                2);
        CodingModelTurnService modelService = new CodingModelTurnService(
                new ProviderCapabilityRegistry(
                        ProviderLane.PRODUCT,
                        ProviderCapabilityPolicy.stage2Baseline(),
                        List.of(registration)),
                gateway, mapper, clock, false);
        ProfileModelBindingService profileModelBindings = mock(ProfileModelBindingService.class);
        when(profileModelBindings.resolve(any(), any(), any(), any()))
                .thenReturn(List.of(registration));
        GuardrailPathSelectionService selections = mock(GuardrailPathSelectionService.class);
        when(selections.jobSnapshot(JOB)).thenReturn(List.of(
                "backend:src/main/java/org/urizo/axmodulestudio/backend/cms"));
        when(selections.jobAreas(JOB)).thenReturn(
                new GuardrailPathSelectionService.JobAreas(List.of("CMS 기능"), List.of()));
        when(selections.jobFiles(JOB)).thenReturn(List.of(
                "src/main/java/org/urizo/axmodulestudio/backend/cms/dto/CmsResponses.java",
                "src/main/java/org/urizo/axmodulestudio/backend/cms/repository/CmsRepository.java"));
        CodingHandlerStageService service = new CodingHandlerStageService(
                resultService, toolService, guard, modelService,
                mock(CodingRunnerService.class), mock(DeploymentAdapter.class), profileModelBindings, selections,
                mock(GuardrailRuleService.class), mapper, clock);
        CodingToolService.StageAuthority authority = new CodingToolService.StageAuthority(
                TRACE, 4,
                UUID.fromString("11111111-1111-4111-8111-111111111111"),
                UUID.fromString("22222222-2222-4222-8222-222222222222"),
                UUID.fromString("33333333-3333-4333-8333-333333333333"),
                UUID.fromString("44444444-4444-4444-8444-444444444444"),
                "coding", BASE_SHA,
                "sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",
                "sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
                "coding-v1",
                Set.of("CHAT", "TOOL_CALLING", "STRUCTURED_OUTPUT"),
                Set.of("coding"),
                Set.copyOf(CodingToolService.CODING_TOOL_SCHEMA_DIGESTS.keySet()),
                NOW.plusSeconds(60),
                PROFILE);
        CodingHandlerContract.AttemptAggregateResponse aggregate =
                new CodingHandlerContract.AttemptAggregateResponse(
                        "1.0", JOB, TRACE, 1, WORKSPACE,
                        CodingHandlerContract.AttemptStatus.ACTIVE,
                        "회원 목록에 가입일도 보이게 해줘",
                        List.of(), List.of(), List.of(), NOW, null);
        when(toolService.stageAuthority(eq("Bearer worker"), eq(JOB), eq(4)))
                .thenReturn(authority);
        when(resultService.aggregate("Bearer worker", JOB, 1)).thenReturn(aggregate);
        when(guard.reserve(eq("Bearer worker"), any())).thenAnswer(invocation -> {
            CodingModelTurnContract.Request turnRequest = invocation.getArgument(1);
            return CodingModelTurnPermit.acquired(
                    turnRequest.jobId(), turnRequest.idempotencyKey(), UUID.randomUUID());
        });
        return new AnalyzeFixture(service, gateway, guard, selections);
    }

    /** A stage with no tools receives the model text verbatim, fence and all. */
    private static ProviderChatResponse assistantText(String content) {
        return new ProviderChatResponse(
                ModelProvider.GOOGLE_GENAI,
                "coding-test-model",
                content,
                12, 6, Duration.ofMillis(10));
    }

    /**
     * Job f2b48a5e measured this: the model applied the patch and passed every check, then
     * closed with a prose summary instead of the declared {"port","payload"} object, and the
     * whole Job died on that one formality. A format miss the model caused is handed back
     * once as feedback - the same discipline as a refused tool call - and the model then
     * repeats its final message in the contract shape.
     */
    @Test
    void reasksOnceWhenTerminalMessageIsProse() {
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
        ProfileModelBindingService profileModelBindings =
                mock(ProfileModelBindingService.class);
        when(profileModelBindings.resolve(
                PROFILE, "code", "coding.code", ModelUseCase.TOOL_CALL))
                .thenReturn(List.of(registration));
        CodingHandlerStageService service = new CodingHandlerStageService(
                resultService, toolService, guard, modelService,
                mock(CodingRunnerService.class), mock(DeploymentAdapter.class), profileModelBindings,
                mock(GuardrailPathSelectionService.class),
                mock(GuardrailRuleService.class), mapper, clock);
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
                NOW.plusSeconds(60),
                PROFILE);
        when(toolService.stageAuthority("Bearer worker", JOB, 4)).thenReturn(authority);
        when(resultService.aggregate("Bearer worker", JOB, 1)).thenReturn(
                new CodingHandlerContract.AttemptAggregateResponse(
                        "1.0", JOB, TRACE, 1, WORKSPACE,
                        CodingHandlerContract.AttemptStatus.ACTIVE,
                        "Implement the approved change.",
                        List.of(), List.of(), List.of(), NOW, null));
        when(guard.reserve(eq("Bearer worker"), any())).thenAnswer(invocation -> {
            CodingModelTurnContract.Request request = invocation.getArgument(1);
            return CodingModelTurnPermit.acquired(
                    request.jobId(), request.idempotencyKey(), UUID.randomUUID());
        });
        when(gateway.chat(any())).thenReturn(
                new ProviderChatResponse(
                        ModelProvider.GOOGLE_GENAI,
                        "coding-test-model",
                        "",
                        List.of(new ProviderChatMessage.ToolCall(
                                "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                                "read_diff",
                                "{}")),
                        10, 5, Duration.ofMillis(10)),
                // The measured miss: a human-facing summary instead of the contract.
                assistantText("완벽합니다! 모든 검증이 통과했습니다. 요청하신 작업이 완료되었습니다."),
                assistantText("{\"port\":\"completed\","
                        + "\"payload\":{\"summary\":\"done\"}}"));
        AtomicReference<UUID> submittedToolCall = new AtomicReference<>();
        when(toolService.submitForNode(
                eq("Bearer worker"), any(), eq("code"))).thenAnswer(invocation -> {
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

        CodingHandlerContract.StageExecutionResponse response = service.execute(
                "Bearer worker", JOB, 1, RESULT,
                new CodingHandlerContract.StageExecutionRequest(
                        "1.0", TRACE, 4, 1, "coding.code", RESULT));

        assertThat(response.resultPort()).isEqualTo("completed");
        assertThat(response.diffDigest()).isEqualTo(DIFF_DIGEST);
        ArgumentCaptor<ProviderChatRequest> modelRequests =
                ArgumentCaptor.forClass(ProviderChatRequest.class);
        verify(gateway, times(3)).chat(modelRequests.capture());
        // The third call carries the correction: the prose answer replayed, then the
        // instruction naming the exact shape the final message must take.
        assertThat(modelRequests.getAllValues().get(2).messages().stream()
                .map(ProviderChatMessage::content).toList().toString())
                .contains("stage result")
                .contains("exactly one JSON object");
    }

    /**
     * Job 7e600583 measured the hole this closes: every apply_patch was refused, so the diff
     * stayed empty, yet read_diff and the deterministic checks all passed because each of
     * them inspected that empty diff, and the Job reached approval carrying no change.
     */
    @Test
    void refusesCodingStageThatChangedNothing() {
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
        ProfileModelBindingService profileModelBindings =
                mock(ProfileModelBindingService.class);
        when(profileModelBindings.resolve(
                PROFILE, "code", "coding.code", ModelUseCase.TOOL_CALL))
                .thenReturn(List.of(registration));
        CodingHandlerStageService service = new CodingHandlerStageService(
                resultService, toolService, guard, modelService,
                mock(CodingRunnerService.class), mock(DeploymentAdapter.class), profileModelBindings,
                mock(GuardrailPathSelectionService.class),
                mock(GuardrailRuleService.class), mapper, clock);
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
                NOW.plusSeconds(60),
                PROFILE);
        when(toolService.stageAuthority("Bearer worker", JOB, 4)).thenReturn(authority);
        when(resultService.aggregate("Bearer worker", JOB, 1)).thenReturn(
                new CodingHandlerContract.AttemptAggregateResponse(
                        "1.0", JOB, TRACE, 1, WORKSPACE,
                        CodingHandlerContract.AttemptStatus.ACTIVE,
                        "Implement the approved change.",
                        List.of(), List.of(), List.of(), NOW, null));
        when(guard.reserve(eq("Bearer worker"), any())).thenAnswer(invocation -> {
            CodingModelTurnContract.Request request = invocation.getArgument(1);
            return CodingModelTurnPermit.acquired(
                    request.jobId(), request.idempotencyKey(), UUID.randomUUID());
        });
        when(gateway.chat(any())).thenReturn(
                new ProviderChatResponse(
                        ModelProvider.GOOGLE_GENAI,
                        "coding-test-model",
                        "",
                        List.of(new ProviderChatMessage.ToolCall(
                                "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                                "read_diff",
                                "{}")),
                        10, 5, Duration.ofMillis(10)),
                // The model claims success even though nothing was applied.
                new ProviderChatResponse(
                        ModelProvider.GOOGLE_GENAI,
                        "coding-test-model",
                        "{\"port\":\"completed\","
                                + "\"payload\":{\"summary\":\"done\"}}",
                        12, 6, Duration.ofMillis(10)));
        AtomicReference<UUID> submittedToolCall = new AtomicReference<>();
        when(toolService.submitForNode(
                eq("Bearer worker"), any(), eq("code"))).thenAnswer(invocation -> {
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
                                + "\"digest\":\"sha256:e3b0c44298fc1c149afbf4c8996fb92427"
                                + "ae41e4649b934ca495991b7852b855\","
                                + "\"changedPaths\":[]}"));

        assertThatThrownBy(() -> service.execute(
                "Bearer worker", JOB, 1, RESULT,
                new CodingHandlerContract.StageExecutionRequest(
                        "1.0", TRACE, 4, 1, "coding.code", RESULT)))
                .isInstanceOf(CodingWorkerException.class)
                .satisfies(failure -> assertThat(((CodingWorkerException) failure).code())
                        .isEqualTo("CODING_DIFF_EMPTY"))
                .hasMessageContaining("without changing any file");
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
        ProfileModelBindingService profileModelBindings =
                mock(ProfileModelBindingService.class);
        when(profileModelBindings.resolve(
                PROFILE, "code", "coding.code", ModelUseCase.TOOL_CALL))
                .thenReturn(List.of(registration));
        CodingHandlerStageService service = new CodingHandlerStageService(
                resultService, toolService, guard, modelService,
                mock(CodingRunnerService.class), mock(DeploymentAdapter.class), profileModelBindings,
                mock(GuardrailPathSelectionService.class),
                mock(GuardrailRuleService.class), mapper, clock);
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
                NOW.plusSeconds(60),
                PROFILE);
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
                        "",
                        List.of(new ProviderChatMessage.ToolCall(
                                "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                                "read_diff",
                                "{}")),
                        10, 5, Duration.ofMillis(10)),
                new ProviderChatResponse(
                        ModelProvider.GOOGLE_GENAI,
                        "coding-test-model",
                        "{\"port\":\"completed\","
                                + "\"payload\":{\"summary\":\"done\"}}",
                        12, 6, Duration.ofMillis(10)));
        AtomicReference<UUID> submittedToolCall = new AtomicReference<>();
        when(toolService.submitForNode(
                eq("Bearer worker"), any(), eq("code"))).thenAnswer(invocation -> {
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
        assertThat(replay.resultId()).isEqualTo(response.resultId());
        assertThat(replay.payload()).isEqualTo(response.payload());

        // The stage's own read_diff, then the model's; the replay submits nothing.
        ArgumentCaptor<JsonNode> toolRequest = ArgumentCaptor.forClass(JsonNode.class);
        verify(toolService, times(2)).submitForNode(
                eq("Bearer worker"), toolRequest.capture(), eq("code"));
        assertThat(toolRequest.getAllValues())
                .extracting(submitted -> submitted.path("tool").path("name").asText())
                .containsExactly("read_diff", "read_diff");
        assertThat(toolRequest.getValue().path("repository").path("candidateSha").asText())
                .isEqualTo(BASE_SHA);

        ArgumentCaptor<ProviderChatRequest> modelRequests =
                ArgumentCaptor.forClass(ProviderChatRequest.class);
        verify(gateway, times(2)).chat(modelRequests.capture());
        // The tool exchange replays natively on the provider path, so the result body
        // arrives in a TOOL-role message rather than as flattened user text.
        assertThat(modelRequests.getAllValues().get(1).messages().stream()
                .filter(message -> message.role() == ProviderChatMessage.Role.TOOL)
                .map(ProviderChatMessage::content)
                .toList().toString())
                .contains(DIFF_DIGEST);
        verify(guard, times(2)).complete(any(), any());
    }

    private static JsonNode changedPaths(String... paths) {
        ObjectNode result = new ObjectMapper().createObjectNode();
        ArrayNode changed = result.putArray("changedPaths");
        for (String path : paths) {
            changed.add(path);
        }
        return result;
    }

    @Test
    void allowedProductWorkPassesThePostCheck() {
        JsonNode diff = changedPaths(
                "src/main/java/org/urizo/axmodulestudio/backend/cms/service/BoardService.java",
                "src/features/cms/MemberListPage.tsx");

        assertThat(CodingHandlerStageService.deniedChangedPaths(diff, diff)).isEmpty();
    }

    @Test
    void aChangedFileInsideTheFixedDenylistIsReported() {
        JsonNode diff = changedPaths(
                "src/main/java/org/urizo/axmodulestudio/backend/cms/service/BoardService.java",
                "src/main/java/org/urizo/axmodulestudio/backend/auth/security/SecurityConfig.java");

        assertThat(CodingHandlerStageService.deniedChangedPaths(diff, diff)).containsExactly(
                "src/main/java/org/urizo/axmodulestudio/backend/auth/security/SecurityConfig.java");
    }

    /**
     * Guide check 6-8. The model plans member work, edits a login file, and reports only the
     * member file. The verdict comes from the tool results, so the report changes nothing.
     */
    @Test
    void aTruthfulLookingReportDoesNotSaveADeniedChange() {
        JsonNode honestLookingDiff = changedPaths(
                "src/main/java/org/urizo/axmodulestudio/backend/cms/controller/MemberController.java");
        JsonNode whatGitActuallySaw = changedPaths(
                "src/main/java/org/urizo/axmodulestudio/backend/cms/controller/MemberController.java",
                "src/main/java/org/urizo/axmodulestudio/backend/auth/security/SecurityConfig.java");

        assertThat(CodingHandlerStageService.deniedChangedPaths(
                honestLookingDiff, whatGitActuallySaw)).containsExactly(
                "src/main/java/org/urizo/axmodulestudio/backend/auth/security/SecurityConfig.java");
    }

    @Test
    void aMigrationEditIsReportedEvenWhenItIsTheOnlyChange() {
        JsonNode diff = changedPaths("src/main/resources/db/migration/V20260901__add_column.sql");

        assertThat(CodingHandlerStageService.deniedChangedPaths(diff, diff)).hasSize(1);
    }

    @Test
    void theSamePathListedByBothToolsIsReportedOnce() {
        JsonNode diff = changedPaths(
                "src/main/java/org/urizo/axmodulestudio/backend/coding/service/CodingToolService.java");

        assertThat(CodingHandlerStageService.deniedChangedPaths(diff, diff)).hasSize(1);
    }

    @Test
    void aToolResultWithoutAChangedPathListIsTreatedAsEmpty() {
        JsonNode empty = new ObjectMapper().createObjectNode();

        assertThat(CodingHandlerStageService.deniedChangedPaths(empty, empty)).isEmpty();
    }

    private static final String CMS_BACKEND =
            "src/main/java/org/urizo/axmodulestudio/backend/cms";

    @Test
    void anEmptySelectionLeavesOnlyTheFixedDenylistInForce() {
        JsonNode diff = changedPaths(CMS_BACKEND + "/service/BoardService.java");

        assertThat(CodingHandlerStageService.outsideAllowedFolders("backend", List.of(), diff, diff))
                .isEmpty();
    }

    @Test
    void aChangeInsideASelectedFolderPasses() {
        JsonNode diff = changedPaths(
                CMS_BACKEND + "/service/BoardService.java",
                CMS_BACKEND + "/controller/MemberController.java");

        assertThat(CodingHandlerStageService.outsideAllowedFolders(
                "backend", List.of("backend:" + CMS_BACKEND), diff, diff)).isEmpty();
    }

    @Test
    void aChangeOutsideEverySelectedFolderIsReported() {
        String health = "src/main/java/org/urizo/axmodulestudio/backend/health/HealthCheck.java";
        JsonNode diff = changedPaths(CMS_BACKEND + "/service/BoardService.java", health);

        assertThat(CodingHandlerStageService.outsideAllowedFolders(
                "backend", List.of("backend:" + CMS_BACKEND), diff, diff)).containsExactly(health);
    }

    @Test
    void aFolderWhoseNameOnlyStartsTheSameIsNotTreatedAsInside() {
        String lookalike = CMS_BACKEND + "-archive/Old.java";
        JsonNode diff = changedPaths(lookalike);

        assertThat(CodingHandlerStageService.outsideAllowedFolders(
                "backend", List.of("backend:" + CMS_BACKEND), diff, diff)).containsExactly(lookalike);
    }

    @Test
    void aChangeInsideThisRepositorysOwnSelectedFolderPasses() {
        JsonNode diff = changedPaths("src/features/cms/MemberListPage.tsx");

        assertThat(CodingHandlerStageService.outsideAllowedFolders(
                "frontend", List.of("frontend:src/features/cms"), diff, diff)).isEmpty();
    }

    /**
     * The prefix used to be discarded and every repository's folders compared against every
     * Job's changes. That was safe only while every Job was a Backend Job, which stopped being
     * true the day a screen request could be made.
     */
    @Test
    void anotherRepositorysSelectedFolderDoesNotOpenThisOne() {
        JsonNode diff = changedPaths("src/features/cms/MemberListPage.tsx");

        assertThat(CodingHandlerStageService.outsideAllowedFolders(
                "frontend", List.of("backend:" + CMS_BACKEND), diff, diff))
                .containsExactly("src/features/cms/MemberListPage.tsx");
    }

    /**
     * Nothing chosen anywhere means nobody has filled the screen in, and the pipeline leaves
     * such a system open. Nothing chosen <em>here</em> is the opposite: the administrator filled
     * it in and left this repository shut. Reading the second as the first would turn a closed
     * repository into an unguarded one.
     */
    @Test
    void aRepositoryLeftOutOfANonEmptyFenceIsShutRatherThanOpen() {
        JsonNode diff = changedPaths("src/app/AppShell.tsx");

        assertThat(CodingHandlerStageService.outsideAllowedFolders(
                "frontend", List.of("backend:" + CMS_BACKEND), diff, diff)).isNotEmpty();
        assertThat(CodingHandlerStageService.outsideAllowedFolders(
                "frontend", List.of(), diff, diff)).isEmpty();
    }

    @Test
    void theSelectedFolderItselfCountsAsInside() {
        JsonNode diff = changedPaths(CMS_BACKEND);

        assertThat(CodingHandlerStageService.outsideAllowedFolders(
                "backend", List.of("backend:" + CMS_BACKEND), diff, diff)).isEmpty();
    }

    private static JsonNode diffBody(String body, String... paths) {
        ObjectNode result = (ObjectNode) changedPaths(paths);
        result.put("diff", body);
        return result;
    }

    /** Two added lines and one removed line, with the file headers a real diff carries. */
    private static final String THREE_CHANGED_LINES = String.join("\n",
            "diff --git a/A.java b/A.java",
            "--- a/A.java",
            "+++ b/A.java",
            "@@ -1,2 +1,3 @@",
            " unchanged",
            "-removed",
            "+added",
            "+added too");

    private static final GuardrailRuleContract.Rules DEFAULT_RULES =
            GuardrailRuleContract.Rules.unrestrictedSize();

    @Test
    void aJobWithNoCopiedRulesIsJudgedByNone() {
        JsonNode diff = diffBody(THREE_CHANGED_LINES, "pom.xml");

        assertThat(CodingHandlerStageService.brokenRules(null, diff, diff)).isEmpty();
    }

    @Test
    void addingALibraryIsRefusedWhileTheRuleIsOff() {
        JsonNode diff = changedPaths(CMS_BACKEND + "/service/BoardService.java", "pom.xml");

        assertThat(CodingHandlerStageService.brokenRules(DEFAULT_RULES, diff, diff))
                .singleElement().asString().contains("pom.xml");
    }

    @Test
    void addingALibraryPassesOnceTheRuleIsOn() {
        JsonNode diff = changedPaths("pom.xml");
        GuardrailRuleContract.Rules allowed =
                new GuardrailRuleContract.Rules(true, null, null);

        assertThat(CodingHandlerStageService.brokenRules(allowed, diff, diff)).isEmpty();
    }

    /** Moving the manifest must not turn a refusal into a pass. */
    @Test
    void aManifestIsRecognisedAtAnyDepth() {
        JsonNode diff = changedPaths("modules/report/pom.xml");

        assertThat(CodingHandlerStageService.brokenRules(DEFAULT_RULES, diff, diff)).hasSize(1);
    }

    /** A lock file brings the dependency in just as the manifest naming it does. */
    @Test
    void aLockFileCountsAsAddingALibrary() {
        JsonNode diff = changedPaths("package-lock.json");

        assertThat(CodingHandlerStageService.brokenRules(DEFAULT_RULES, diff, diff)).hasSize(1);
    }

    @Test
    void ordinaryProductWorkBreaksNoRule() {
        JsonNode diff = diffBody(THREE_CHANGED_LINES,
                CMS_BACKEND + "/service/BoardService.java");

        assertThat(CodingHandlerStageService.brokenRules(DEFAULT_RULES, diff, diff)).isEmpty();
    }

    @Test
    void anUnsetLimitRefusesNothingHowLargeTheChange() {
        JsonNode diff = diffBody(THREE_CHANGED_LINES,
                CMS_BACKEND + "/A.java", CMS_BACKEND + "/B.java", CMS_BACKEND + "/C.java");

        assertThat(CodingHandlerStageService.brokenRules(DEFAULT_RULES, diff, diff)).isEmpty();
    }

    @Test
    void tooManyChangedFilesIsRefused() {
        JsonNode diff = changedPaths(
                CMS_BACKEND + "/A.java", CMS_BACKEND + "/B.java", CMS_BACKEND + "/C.java");
        GuardrailRuleContract.Rules twoFiles =
                new GuardrailRuleContract.Rules(false, 2, null);

        assertThat(CodingHandlerStageService.brokenRules(twoFiles, diff, diff))
                .singleElement().asString().contains("3 files");
    }

    @Test
    void exactlyTheFileLimitPasses() {
        JsonNode diff = changedPaths(CMS_BACKEND + "/A.java", CMS_BACKEND + "/B.java");
        GuardrailRuleContract.Rules twoFiles =
                new GuardrailRuleContract.Rules(false, 2, null);

        assertThat(CodingHandlerStageService.brokenRules(twoFiles, diff, diff)).isEmpty();
    }

    /** Only the body counts: the +++ and --- headers are not changed lines. */
    @Test
    void diffHeadersAreNotCountedAsChangedLines() {
        JsonNode diff = diffBody(THREE_CHANGED_LINES, CMS_BACKEND + "/A.java");
        GuardrailRuleContract.Rules threeLines =
                new GuardrailRuleContract.Rules(false, null, 3);

        assertThat(CodingHandlerStageService.brokenRules(threeLines, diff, diff)).isEmpty();
    }

    @Test
    void tooManyChangedLinesIsRefused() {
        JsonNode diff = diffBody(THREE_CHANGED_LINES, CMS_BACKEND + "/A.java");
        GuardrailRuleContract.Rules twoLines =
                new GuardrailRuleContract.Rules(false, null, 2);

        assertThat(CodingHandlerStageService.brokenRules(twoLines, diff, diff))
                .singleElement().asString().contains("3 lines");
    }

    /** Both tools describe the same diff, so the body must not be counted twice. */
    @Test
    void theSameDiffSeenByBothToolsIsCountedOnce() {
        JsonNode diff = diffBody(THREE_CHANGED_LINES, CMS_BACKEND + "/A.java");
        GuardrailRuleContract.Rules threeLines =
                new GuardrailRuleContract.Rules(false, null, 3);

        assertThat(CodingHandlerStageService.brokenRules(threeLines, diff, diff)).isEmpty();
    }

    @Test
    void everyBrokenRuleIsReportedTogether() {
        JsonNode diff = diffBody(THREE_CHANGED_LINES, "pom.xml", CMS_BACKEND + "/A.java");
        GuardrailRuleContract.Rules strict =
                new GuardrailRuleContract.Rules(false, 1, 2);

        assertThat(CodingHandlerStageService.brokenRules(strict, diff, diff)).hasSize(3);
    }

    // ---- 6-8: the preview stage itself, not just the verdict helper ----
    //
    // The helpers above prove what the verdict is. These prove the preview stage asks for it at
    // all, and that a denied verdict stops the candidate before anything is queued. A guardrail
    // that is computed and then ignored looks identical to one that works.

    private static final String DENIED_LOGIN_FILE =
            "src/main/java/org/urizo/axmodulestudio/backend/auth/security/SecurityConfig.java";
    private static final String ALLOWED_MEMBER_FILE =
            "src/main/java/org/urizo/axmodulestudio/backend/cms/controller/MemberController.java";

    /**
     * Drives {@code coding.preview} with the four deterministic tools stubbed, so the only thing
     * under test is what preview does with what Git reported.
     */
    private CodingHandlerContract.StageExecutionResponse runPreview(
            CodingRunnerService runner,
            GuardrailPathSelectionService selections,
            GuardrailRuleService rules,
            List<String> gitReportedPaths,
            String diffBody,
            String jobRepository) {
        ObjectMapper mapper = new ObjectMapper();
        CodingHandlerResultService resultService = mock(CodingHandlerResultService.class);
        // The queued build names a repository, and it reads that from the Job rather than
        // assuming one. Left unstubbed the payload would carry null and say nothing.
        when(resultService.jobRepository(JOB)).thenReturn(jobRepository);
        CodingToolService toolService = mock(CodingToolService.class);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        CodingHandlerStageService service = new CodingHandlerStageService(
                resultService, toolService, mock(CodingModelTurnGuard.class),
                mock(CodingModelTurnService.class), runner,
                mock(DeploymentAdapter.class),
                mock(ProfileModelBindingService.class), selections, rules, mapper, clock);

        CodingToolService.StageAuthority authority = new CodingToolService.StageAuthority(
                TRACE, 4,
                UUID.fromString("11111111-1111-4111-8111-111111111111"),
                UUID.fromString("22222222-2222-4222-8222-222222222222"),
                UUID.fromString("33333333-3333-4333-8333-333333333333"),
                UUID.fromString("44444444-4444-4444-8444-444444444444"),
                "coding", BASE_SHA,
                "sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",
                "sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
                "coding-v1",
                Set.of("CHAT", "TOOL_CALLING"), Set.of("coding"),
                Set.copyOf(CodingToolService.CODING_TOOL_SCHEMA_DIGESTS.keySet()),
                NOW.plusSeconds(60), PROFILE);
        when(toolService.stageAuthority("Bearer worker", JOB, 4)).thenReturn(authority);

        // The model reported member work only. What Git saw is the argument instead.
        CodingHandlerContract.HandlerResultResponse code =
                new CodingHandlerContract.HandlerResultResponse(
                        "1.0", UUID.randomUUID(), JOB, TRACE, 1, "coding.code",
                        CodingHandlerContract.ResultType.CANDIDATE, "completed",
                        WORKSPACE, BASE_SHA, DIFF_DIGEST, null,
                        mapper.createObjectNode().put("summary", "member files only"), NOW);
        CodingHandlerContract.HandlerResultResponse review =
                new CodingHandlerContract.HandlerResultResponse(
                        "1.0", UUID.randomUUID(), JOB, TRACE, 1, "coding.review",
                        CodingHandlerContract.ResultType.CANDIDATE, "passed",
                        WORKSPACE, BASE_SHA, DIFF_DIGEST, null,
                        mapper.createObjectNode(), NOW);
        when(resultService.aggregate("Bearer worker", JOB, 1)).thenReturn(
                new CodingHandlerContract.AttemptAggregateResponse(
                        "1.0", JOB, TRACE, 1, WORKSPACE,
                        CodingHandlerContract.AttemptStatus.ACTIVE,
                        "Change the member screen only.",
                        List.of(code, review), List.of(), List.of(), NOW, null));

        AtomicReference<String> pendingTool = new AtomicReference<>();
        List<String> requestedTools = new ArrayList<>();
        when(toolService.submitForNode(
                eq("Bearer worker"), any(), eq("preview"))).thenAnswer(invocation -> {
            JsonNode submitted = invocation.getArgument(1);
            pendingTool.set(submitted.path("tool").path("name").asText());
            requestedTools.add(pendingTool.get());
            return new CodingToolContract.Accepted(
                    "1.0", "TOOL_ACCEPTED",
                    UUID.fromString(submitted.path("requestId").asText()),
                    UUID.fromString(submitted.path("toolCallId").asText()),
                    JOB, TRACE, submitted.path("idempotencyKey").asText(), EXECUTION,
                    "ACCEPTED", "/internal/coding/tool-executions/" + EXECUTION, 100, NOW);
        });
        ArrayNode paths = mapper.createArrayNode();
        gitReportedPaths.forEach(paths::add);
        when(toolService.result("Bearer worker", EXECUTION)).thenAnswer(ignored -> {
            ObjectNode body = mapper.createObjectNode();
            switch (pendingTool.get()) {
                case "read_diff" -> {
                    body.put("digest", DIFF_DIGEST);
                    body.set("changedPaths", paths.deepCopy());
                    body.put("diff", diffBody);
                }
                case "run_check" -> {
                    body.put("status", "PASSED");
                    body.put("profile", "git-diff-check");
                    body.put("detailsDigest", DIFF_DIGEST);
                }
                case "check_package_allowlist" -> {
                    body.put("passed", true);
                    body.put("diffDigest", DIFF_DIGEST);
                }
                default -> {
                    body.put("passed", true);
                    body.put("diffDigest", DIFF_DIGEST);
                    body.set("changedPaths", paths.deepCopy());
                }
            }
            return new CodingToolContract.ResultContent(
                    "1.0", UUID.randomUUID(), UUID.randomUUID(), JOB, TRACE,
                    "stage-tool.result", EXECUTION, "application/json", 120,
                    "sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
                    body.toString());
        });
        CodingHandlerContract.StageExecutionResponse response = service.execute(
                "Bearer worker", JOB, 1, RESULT,
                new CodingHandlerContract.StageExecutionRequest(
                        "1.0", TRACE, 4, 1, "coding.preview", RESULT));
        assertThat(requestedTools).containsExactly(
                "read_diff", "run_check", "check_package_allowlist", "scan_changed_files");
        verify(toolService, times(4)).submitForNode(
                eq("Bearer worker"), any(), eq("preview"));
        return response;
    }

    /**
     * Guide check 6-8, at the stage rather than at the helper. The model's own summary says member
     * work; Git says a login file changed. Nothing is queued and the preview never becomes READY.
     */
    @Test
    void previewRefusesADeniedPathAndQueuesNothing() {
        CodingRunnerService runner = mock(CodingRunnerService.class);

        assertThatThrownBy(() -> runPreview(
                runner, mock(GuardrailPathSelectionService.class),
                mock(GuardrailRuleService.class),
                List.of(ALLOWED_MEMBER_FILE, DENIED_LOGIN_FILE), null, "backend"))
                .isInstanceOf(CodingWorkerException.class)
                .hasMessageContaining(DENIED_LOGIN_FILE);

        // No BUILD and no PREVIEW_UP. A refused candidate must not reach Docker at all.
        verify(runner, never()).enqueue(any(), any());
        // Nor PREVIEW_DOWN. It is queued under a derived id, which is a different overload,
        // so the check above would not see it and someone else's preview would go down for a
        // candidate that never got as far as being built.
        verify(runner, never()).enqueue(any(UUID.class), any(), any());
    }

    /**
     * A preview left up by an earlier Job is six containers still running, and the only place
     * that took one down was past the GITHUB approval. The build and test queued below competed
     * with them for the same CPU: the same candidate failed its check twice with a preview up
     * and passed with it down. The runner claims one pending row at a time in created order, so
     * being queued first is what makes it happen first.
     */
    @Test
    void previewTakesAnEarlierPreviewDownBeforeTheCandidateIsBuiltAndChecked() {
        CodingRunnerService runner = mock(CodingRunnerService.class);

        runPreview(runner, mock(GuardrailPathSelectionService.class),
                mock(GuardrailRuleService.class),
                List.of(ALLOWED_MEMBER_FILE), null, "frontend");

        InOrder order = inOrder(runner);
        order.verify(runner).enqueue(any(UUID.class), eq("PREVIEW_DOWN"), any());
        order.verify(runner).enqueue(eq("BUILD"), any());
        order.verify(runner).enqueue(eq("TEST"), any());
        order.verify(runner).enqueue(eq("PREVIEW_UP"), any());
    }

    /**
     * The same stage runs again on a retry. A derived id rather than a random one is what keeps
     * the retry from queueing a second teardown row.
     */
    @Test
    void previewDerivesTheTeardownTaskIdSoARetryDoesNotQueueASecondOne() {
        CodingRunnerService first = mock(CodingRunnerService.class);
        CodingRunnerService second = mock(CodingRunnerService.class);

        runPreview(first, mock(GuardrailPathSelectionService.class),
                mock(GuardrailRuleService.class), List.of(ALLOWED_MEMBER_FILE), null, "backend");
        runPreview(second, mock(GuardrailPathSelectionService.class),
                mock(GuardrailRuleService.class), List.of(ALLOWED_MEMBER_FILE), null, "backend");

        assertThat(teardownTaskId(first)).isEqualTo(teardownTaskId(second));
    }

    /** The task id one run queued its preview teardown under. */
    private static UUID teardownTaskId(CodingRunnerService runner) {
        ArgumentCaptor<UUID> taskId = ArgumentCaptor.forClass(UUID.class);
        verify(runner).enqueue(taskId.capture(), eq("PREVIEW_DOWN"), any());
        return taskId.getValue();
    }

    @Test
    void previewBecomesReadyWhenEveryChangedPathIsAllowed() {
        CodingRunnerService runner = mock(CodingRunnerService.class);

        CodingHandlerContract.StageExecutionResponse response = runPreview(
                runner, mock(GuardrailPathSelectionService.class),
                mock(GuardrailRuleService.class),
                List.of(ALLOWED_MEMBER_FILE), null, "backend");

        assertThat(response.resultPort()).isEqualTo("ready");
        assertThat(response.payload().path("status").asText()).isEqualTo("READY");
        assertThat(queuedRepository(runner, "BUILD")).isEqualTo("backend");
        assertThat(queuedRepository(runner, "PREVIEW_UP")).isEqualTo("backend");
    }

    /**
     * The repository used to be written into the queued commands as the literal "backend",
     * because it was the only one a Job could be in. A frontend Job must reach the runner as
     * a frontend Job: naming the wrong one builds one repository from another's checkout.
     */
    @Test
    void previewQueuesTheJobsOwnRepositoryRatherThanAFixedOne() {
        CodingRunnerService runner = mock(CodingRunnerService.class);

        runPreview(runner, mock(GuardrailPathSelectionService.class),
                mock(GuardrailRuleService.class),
                List.of(ALLOWED_MEMBER_FILE), null, "frontend");

        assertThat(queuedRepository(runner, "BUILD")).isEqualTo("frontend");
        assertThat(queuedRepository(runner, "PREVIEW_UP")).isEqualTo("frontend");
    }

    /**
     * The frontend runtime image installs and serves without compiling or testing, so nothing
     * would stand between a broken screen and the person asked to approve it.
     */
    @Test
    void previewChecksAFrontendCandidateBeforeAnyoneIsAskedToApproveIt() {
        CodingRunnerService runner = mock(CodingRunnerService.class);

        runPreview(runner, mock(GuardrailPathSelectionService.class),
                mock(GuardrailRuleService.class),
                List.of(ALLOWED_MEMBER_FILE), null, "frontend");

        assertThat(queuedRepository(runner, "TEST")).isEqualTo("frontend");
    }

    /** A backend candidate has to compile to become an image at all, so BUILD is that check. */
    @Test
    void previewDoesNotQueueASeparateCheckForABackendCandidate() {
        CodingRunnerService runner = mock(CodingRunnerService.class);

        runPreview(runner, mock(GuardrailPathSelectionService.class),
                mock(GuardrailRuleService.class),
                List.of(ALLOWED_MEMBER_FILE), null, "backend");

        verify(runner, never()).enqueue(eq("TEST"), any());
    }

    /** The "repo" the runner is told to work in, for one queued command kind. */
    private static String queuedRepository(CodingRunnerService runner, String kind) {
        ArgumentCaptor<com.fasterxml.jackson.databind.JsonNode> payload =
                ArgumentCaptor.forClass(com.fasterxml.jackson.databind.JsonNode.class);
        verify(runner).enqueue(eq(kind), payload.capture());
        return payload.getValue().path("repo").asText();
    }

    /** The second layer is asked for too, using the copy taken when the job was created. */
    @Test
    void previewRefusesAPathOutsideTheSelectedFolders() {
        CodingRunnerService runner = mock(CodingRunnerService.class);
        GuardrailPathSelectionService selections = mock(GuardrailPathSelectionService.class);
        when(selections.jobSnapshot(JOB)).thenReturn(List.of("backend:" + CMS_BACKEND));

        assertThatThrownBy(() -> runPreview(
                runner, selections, mock(GuardrailRuleService.class),
                List.of("src/main/java/org/urizo/axmodulestudio/backend/health/HealthCheck.java"),
                null, "backend"))
                .isInstanceOf(CodingWorkerException.class)
                .hasMessageContaining("outside the selected folders");

        verify(runner, never()).enqueue(any(), any());
        verify(runner, never()).enqueue(any(UUID.class), any(), any());
    }

    /** The third layer is asked for too, using the rules copied for this job. */
    @Test
    void previewRefusesAChangeThatBreaksTheCopiedRules() {
        CodingRunnerService runner = mock(CodingRunnerService.class);
        GuardrailRuleService rules = mock(GuardrailRuleService.class);
        when(rules.jobRules(JOB)).thenReturn(
                java.util.Optional.of(new GuardrailRuleContract.Rules(false, null, null)));

        assertThatThrownBy(() -> runPreview(
                runner, mock(GuardrailPathSelectionService.class), rules,
                List.of(ALLOWED_MEMBER_FILE, "pom.xml"), null, "backend"))
                .isInstanceOf(CodingWorkerException.class)
                .hasMessageContaining("adding a library is not allowed");

        verify(runner, never()).enqueue(any(), any());
        verify(runner, never()).enqueue(any(UUID.class), any(), any());
    }

    @Test
    void reviewIsAskedForAPlainLanguageReportAndIsGivenTheAgreedCriteria() throws Exception {
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
        ProfileModelBindingService profileModelBindings =
                mock(ProfileModelBindingService.class);
        when(profileModelBindings.resolve(
                PROFILE, "review", "coding.review", ModelUseCase.TOOL_CALL))
                .thenReturn(List.of(registration));
        CodingHandlerStageService service = new CodingHandlerStageService(
                resultService, toolService, guard, modelService,
                mock(CodingRunnerService.class), mock(DeploymentAdapter.class), profileModelBindings,
                mock(GuardrailPathSelectionService.class),
                mock(GuardrailRuleService.class), mapper, clock);
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
                NOW.plusSeconds(60),
                PROFILE);
        // Approval 1 agreed these criteria. Approval 2 has to show them against the outcome,
        // so the review stage must receive them rather than invent its own.
        CodingHandlerContract.HandlerResultResponse analysis =
                new CodingHandlerContract.HandlerResultResponse(
                        "1.0", UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
                        JOB, TRACE, 1, "coding.analyze",
                        CodingHandlerContract.ResultType.ANALYSIS, "feasible",
                        WORKSPACE, null, null, null,
                        mapper.readTree("{\"planSummary\":\"가입일을 목록에 더합니다.\","
                                + "\"acceptanceCriteria\":[\"목록에 가입일이 보인다\"]}"),
                        NOW);
        CodingHandlerContract.HandlerResultResponse code =
                new CodingHandlerContract.HandlerResultResponse(
                        "1.0", UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc"),
                        JOB, TRACE, 1, "coding.code",
                        CodingHandlerContract.ResultType.CANDIDATE, "completed",
                        WORKSPACE, BASE_SHA, DIFF_DIGEST, null,
                        mapper.createObjectNode(), NOW);
        CodingHandlerContract.AttemptAggregateResponse aggregate =
                new CodingHandlerContract.AttemptAggregateResponse(
                        "1.0", JOB, TRACE, 1, WORKSPACE,
                        CodingHandlerContract.AttemptStatus.ACTIVE,
                        "회원 목록에 가입일도 보이게 해줘",
                        List.of(analysis, code), List.of(), List.of(), NOW, null);
        when(toolService.stageAuthority("Bearer worker", JOB, 4)).thenReturn(authority);
        when(resultService.aggregate("Bearer worker", JOB, 1)).thenReturn(aggregate);
        when(guard.reserve(eq("Bearer worker"), any())).thenAnswer(invocation -> {
            CodingModelTurnContract.Request turnRequest = invocation.getArgument(1);
            return CodingModelTurnPermit.acquired(
                    turnRequest.jobId(), turnRequest.idempotencyKey(), UUID.randomUUID());
        });
        when(gateway.chat(any())).thenReturn(new ProviderChatResponse(
                ModelProvider.GOOGLE_GENAI, "coding-test-model",
                "{\"port\":\"passed\",\"payload\":{\"reportSummary\":\"됐습니다\","
                        + "\"criteriaResults\":[]}}",
                12, 6, Duration.ofMillis(10)));

        service.execute("Bearer worker", JOB, 1, RESULT,
                new CodingHandlerContract.StageExecutionRequest(
                        "1.0", TRACE, 4, 1, "coding.review", RESULT));

        // The review stage runs no read_diff of its own before the model answers.
        verify(toolService, never()).submitForNode(any(), any(), any());
        ArgumentCaptor<ProviderChatRequest> sent =
                ArgumentCaptor.forClass(ProviderChatRequest.class);
        verify(gateway).chat(sent.capture());
        String system = sent.getValue().messages().stream()
                .filter(message -> message.role() == ProviderChatMessage.Role.SYSTEM)
                .map(ProviderChatMessage::content)
                .toList().toString();
        String user = sent.getValue().messages().stream()
                .filter(message -> message.role() == ProviderChatMessage.Role.USER)
                .map(ProviderChatMessage::content)
                .toList().toString();
        // The order asks for the two fields approval 2 renders.
        assertThat(system).contains("reportSummary").contains("criteriaResults");
        // And the criteria agreed at approval 1 actually reach the reviewer.
        assertThat(user).contains("acceptanceCriteria");
        // The reviewer's context carries neither of the code stage's pre-read structures.
        assertThat(user).doesNotContain("targetFileOutlines").doesNotContain("targetFileExcerpts");
    }

    // Measured on Jobs a4dd06bf and c26fd4aa: the request was inside the fence and correctly
    // accepted, and the reviewer then asked for the test asserting the changed string - a file
    // outside the fence - to be updated too. The coding stage complied and the post-check
    // refused the candidate. The reviewer is the first stage that can see the conflict, so it
    // is shown the same areas the analyst gets and asked to name the case.
    @Test
    void theReviewerIsShownTheFenceAsAreasAndAskedWhetherFinishingNeedsADeniedOne()
            throws Exception {
        GuardrailPathSelectionService selections = mock(GuardrailPathSelectionService.class);
        when(selections.jobSnapshot(JOB)).thenReturn(List.of(
                "backend:src/main/java/org/urizo/axmodulestudio/backend/cms"));
        when(selections.jobAreas(JOB)).thenReturn(
                new GuardrailPathSelectionService.JobAreas(
                        List.of("CMS 기능"), List.of("앱 뼈대", "상태 점검")));
        when(selections.jobFiles(JOB)).thenReturn(List.of(
                "src/main/java/org/urizo/axmodulestudio/backend/cms/dto/CmsResponses.java"));

        ProviderChatRequest sent = captureReviewRequest(selections);
        String system = systemContent(sent);
        String user = userContent(sent);

        // The field the rework gate reads. Without it "not finished yet" and "cannot be done
        // here" both arrive as changes_requested and the gate sends the job back to coding.
        assertThat(system).contains("requiresDeniedArea");
        assertThat(system).contains("guardrail.allowedAreas");
        // A denied verdict still returns changes_requested; only the flag separates the cases.
        assertThat(system).contains("\"changes_requested\"");
        assertThat(user).contains("CMS 기능");
        assertThat(user).contains("앱 뼈대");
    }

    // reportSummary reaches the same general administrator as planSummary, and a model quotes
    // what it was shown. The reviewer therefore gets the area labels and never the fence's file
    // list or the repository-prefixed paths behind it.
    @Test
    void theReviewerIsNeverShownTheFencesPathsOrFileList() throws Exception {
        GuardrailPathSelectionService selections = mock(GuardrailPathSelectionService.class);
        when(selections.jobSnapshot(JOB)).thenReturn(List.of(
                "backend:src/main/java/org/urizo/axmodulestudio/backend/cms"));
        when(selections.jobAreas(JOB)).thenReturn(
                new GuardrailPathSelectionService.JobAreas(
                        List.of("CMS 기능"), List.of("앱 뼈대")));
        when(selections.jobFiles(JOB)).thenReturn(List.of(
                "src/main/java/org/urizo/axmodulestudio/backend/cms/dto/CmsResponses.java"));

        ProviderChatRequest sent = captureReviewRequest(selections);
        String user = userContent(sent);

        assertThat(user).doesNotContain("CmsResponses.java");
        assertThat(user)
                .doesNotContain("backend:src/main/java/org/urizo/axmodulestudio/backend/cms");
        assertThat(systemContent(sent)).doesNotContain("guardrail.files");
    }

    // An open system has no fence to show, and injecting a fabricated one would have the
    // reviewer refuse work the post-check would have passed.
    @Test
    void anOpenSystemLeavesTheReviewerWithNoFenceAtAll() throws Exception {
        ProviderChatRequest sent =
                captureReviewRequest(mock(GuardrailPathSelectionService.class));

        assertThat(userContent(sent)).doesNotContain("guardrail");
    }

    // The phrase matches carry paths, and reportSummary reaches the same general administrator
    // as planSummary. The reviewer is given neither the list nor the instruction about it.
    @Test
    void theReviewerIsNeverShownThePhraseMatches() throws Exception {
        GuardrailPathSelectionService selections = mock(GuardrailPathSelectionService.class);
        when(selections.jobSnapshot(JOB)).thenReturn(List.of("frontend:src/features/site"));
        when(selections.jobAreas(JOB)).thenReturn(
                new GuardrailPathSelectionService.JobAreas(List.of("사이트 화면"), List.of()));
        when(selections.jobPhraseMatches(JOB)).thenReturn(List.of(
                new GuardrailJobSnapshotWriter.PhraseMatch("지금 열리는 축제·행사",
                        "src/features/site/TourPortal.tsx", 307, "<h2>지금 열리는 축제·행사</h2>")));

        ProviderChatRequest sent = captureReviewRequest(selections);

        assertThat(userContent(sent)).doesNotContain("phraseMatches")
                .doesNotContain("TourPortal.tsx");
        assertThat(systemContent(sent)).doesNotContain("guardrail.phraseMatches");
    }

    // One request run 18 times: from names alone the file holding the quoted heading was chosen
    // by Gemini 11/11, haiku 2/4 and nano 1/3. The scan's matches reach the analyst with one
    // sentence saying what they are and that the choice is still its own.
    @Test
    void theAnalystIsShownWhereTheQuotedTextAlreadyAppears() throws Exception {
        GuardrailPathSelectionService selections = phraseFence();
        when(selections.jobPhraseMatches(JOB)).thenReturn(List.of(
                new GuardrailJobSnapshotWriter.PhraseMatch("지금 열리는 축제·행사",
                        "src/features/site/TourPortal.tsx", 307, "<h2>지금 열리는 축제·행사</h2>")));

        ProviderChatRequest sent = captureAnalysisRequest(selections);

        assertThat(userContent(sent)).contains("phraseMatches")
                .contains("<h2>지금 열리는 축제·행사</h2>")
                .contains("307");
        assertThat(systemContent(sent)).contains("guardrail.phraseMatches")
                .contains("It is a hint, not the answer.");
    }

    // Also the shape of every job created before the search existed: its copy has no list.
    @Test
    void anAnalystWithNoPhraseMatchesSeesNeitherTheListNorTheInstruction() throws Exception {
        ProviderChatRequest sent = captureAnalysisRequest(phraseFence());

        assertThat(userContent(sent)).doesNotContain("phraseMatches").contains("사이트 화면");
        assertThat(systemContent(sent)).doesNotContain("guardrail.phraseMatches");
    }

    // Jobs 543eb70f, b3a872c3, efadcf37 and c54876c1 (nano): eight of ten rejections said only
    // that the diff could not confirm a criterion, three of them after reading "all four cards
    // are shown again" as "exactly four must always render". The rule belongs to the reviewer;
    // the analyst writes the criteria and is not told how they will be judged.
    @Test
    void theReviewerMarksACriterionUnmetOnlyWhenTheDiffContradictsIt() throws Exception {
        String review = systemContent(captureReviewRequest(phraseFence()));
        String analysis = systemContent(captureAnalysisRequest(phraseFence()));

        assertThat(review)
                .contains("Set met to false only when a changed line in the diff, or a failed "
                        + "check, contradicts the criterion.")
                .contains("A criterion is not false because the diff does not show it.")
                .contains("Do not reinterpret what the request states about the existing "
                        + "screen, such as how many items it shows, as a condition with a "
                        + "different meaning.");
        assertThat(analysis).doesNotContain("Set met to false only when")
                .doesNotContain("Do not reinterpret what the request states");
    }

    private static GuardrailPathSelectionService phraseFence() {
        GuardrailPathSelectionService selections = mock(GuardrailPathSelectionService.class);
        when(selections.jobSnapshot(JOB)).thenReturn(List.of("frontend:src/features/site"));
        when(selections.jobAreas(JOB)).thenReturn(
                new GuardrailPathSelectionService.JobAreas(List.of("사이트 화면"), List.of()));
        when(selections.jobFiles(JOB)).thenReturn(List.of("src/features/site/TourPortal.tsx"));
        return selections;
    }

    /** Runs one analysis stage against the given fence and returns the request the gateway saw. */
    private ProviderChatRequest captureAnalysisRequest(
            GuardrailPathSelectionService selections) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        CodingHandlerResultService resultService = mock(CodingHandlerResultService.class);
        CodingToolService toolService = mock(CodingToolService.class);
        CodingModelTurnGuard guard = mock(CodingModelTurnGuard.class);
        ProviderChatGatewayPort gateway = mock(ProviderChatGatewayPort.class);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        ProviderModelRegistration registration = new ProviderModelRegistration(
                ModelProvider.GOOGLE_GENAI,
                "coding-test-model",
                Set.of(
                        ModelCapability.CHAT,
                        ModelCapability.TOOL_CALLING,
                        ModelCapability.STRUCTURED_OUTPUT),
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
        ProfileModelBindingService profileModelBindings =
                mock(ProfileModelBindingService.class);
        when(profileModelBindings.resolve(
                PROFILE, "analyze", "coding.analyze", ModelUseCase.STRUCTURED_OUTPUT))
                .thenReturn(List.of(registration));
        CodingHandlerStageService service = new CodingHandlerStageService(
                resultService, toolService, guard, modelService,
                mock(CodingRunnerService.class), mock(DeploymentAdapter.class),
                profileModelBindings, selections,
                mock(GuardrailRuleService.class), mapper, clock);
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
                NOW.plusSeconds(60),
                PROFILE);
        CodingHandlerContract.AttemptAggregateResponse aggregate =
                new CodingHandlerContract.AttemptAggregateResponse(
                        "1.0", JOB, TRACE, 1, WORKSPACE,
                        CodingHandlerContract.AttemptStatus.ACTIVE,
                        "홈 화면 '지금 열리는 축제·행사' 에 진행 중만 보는 버튼을 넣어줘",
                        List.of(), List.of(), List.of(), NOW, null);
        when(toolService.stageAuthority(eq("Bearer worker"), eq(JOB), eq(4)))
                .thenReturn(authority);
        when(resultService.aggregate("Bearer worker", JOB, 1)).thenReturn(aggregate);
        when(guard.reserve(eq("Bearer worker"), any())).thenAnswer(invocation -> {
            CodingModelTurnContract.Request turnRequest = invocation.getArgument(1);
            return CodingModelTurnPermit.acquired(
                    turnRequest.jobId(), turnRequest.idempotencyKey(), UUID.randomUUID());
        });
        when(gateway.chat(any())).thenReturn(assistantText(
                "{\"port\":\"feasible\",\"payload\":{\"planSummary\":\"버튼을 넣습니다.\","
                        + "\"acceptanceCriteria\":[\"버튼이 보인다\"],"
                        + "\"targetFiles\":[\"src/features/site/TourPortal.tsx\"]}}"));

        service.execute("Bearer worker", JOB, 1, RESULT,
                new CodingHandlerContract.StageExecutionRequest(
                        "1.0", TRACE, 4, 1, "coding.analyze", RESULT));

        ArgumentCaptor<ProviderChatRequest> sent =
                ArgumentCaptor.forClass(ProviderChatRequest.class);
        verify(gateway).chat(sent.capture());
        return sent.getValue();
    }

    /** Runs one review stage against the given fence and returns the request the gateway saw. */
    private ProviderChatRequest captureReviewRequest(
            GuardrailPathSelectionService selections) throws Exception {
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
                1);
        CodingModelTurnService modelService = new CodingModelTurnService(
                new ProviderCapabilityRegistry(
                        ProviderLane.PRODUCT,
                        ProviderCapabilityPolicy.stage2Baseline(),
                        List.of(registration)),
                gateway,
                mapper,
                clock,
                false);
        ProfileModelBindingService profileModelBindings =
                mock(ProfileModelBindingService.class);
        when(profileModelBindings.resolve(
                PROFILE, "review", "coding.review", ModelUseCase.TOOL_CALL))
                .thenReturn(List.of(registration));
        CodingHandlerStageService service = new CodingHandlerStageService(
                resultService, toolService, guard, modelService,
                mock(CodingRunnerService.class), mock(DeploymentAdapter.class),
                profileModelBindings, selections,
                mock(GuardrailRuleService.class), mapper, clock);
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
                NOW.plusSeconds(60),
                PROFILE);
        CodingHandlerContract.HandlerResultResponse analysis =
                new CodingHandlerContract.HandlerResultResponse(
                        "1.0", UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
                        JOB, TRACE, 1, "coding.analyze",
                        CodingHandlerContract.ResultType.ANALYSIS, "feasible",
                        WORKSPACE, null, null, null,
                        mapper.readTree("{\"planSummary\":\"문구를 바꿉니다.\","
                                + "\"acceptanceCriteria\":[\"문구가 바뀐다\"]}"),
                        NOW);
        CodingHandlerContract.HandlerResultResponse code =
                new CodingHandlerContract.HandlerResultResponse(
                        "1.0", UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc"),
                        JOB, TRACE, 1, "coding.code",
                        CodingHandlerContract.ResultType.CANDIDATE, "completed",
                        WORKSPACE, BASE_SHA, DIFF_DIGEST, null,
                        mapper.createObjectNode(), NOW);
        CodingHandlerContract.AttemptAggregateResponse aggregate =
                new CodingHandlerContract.AttemptAggregateResponse(
                        "1.0", JOB, TRACE, 1, WORKSPACE,
                        CodingHandlerContract.AttemptStatus.ACTIVE,
                        "화면 문구를 바꿔줘",
                        List.of(analysis, code), List.of(), List.of(), NOW, null);
        when(toolService.stageAuthority("Bearer worker", JOB, 4)).thenReturn(authority);
        when(resultService.aggregate("Bearer worker", JOB, 1)).thenReturn(aggregate);
        when(guard.reserve(eq("Bearer worker"), any())).thenAnswer(invocation -> {
            CodingModelTurnContract.Request turnRequest = invocation.getArgument(1);
            return CodingModelTurnPermit.acquired(
                    turnRequest.jobId(), turnRequest.idempotencyKey(), UUID.randomUUID());
        });
        when(gateway.chat(any())).thenReturn(new ProviderChatResponse(
                ModelProvider.GOOGLE_GENAI, "coding-test-model",
                "{\"port\":\"passed\",\"payload\":{\"reportSummary\":\"됐습니다\","
                        + "\"criteriaResults\":[],\"requiresDeniedArea\":false}}",
                12, 6, Duration.ofMillis(10)));

        service.execute("Bearer worker", JOB, 1, RESULT,
                new CodingHandlerContract.StageExecutionRequest(
                        "1.0", TRACE, 4, 1, "coding.review", RESULT));

        ArgumentCaptor<ProviderChatRequest> sent =
                ArgumentCaptor.forClass(ProviderChatRequest.class);
        verify(gateway).chat(sent.capture());
        return sent.getValue();
    }

    private static String systemContent(ProviderChatRequest request) {
        return request.messages().stream()
                .filter(message -> message.role() == ProviderChatMessage.Role.SYSTEM)
                .map(ProviderChatMessage::content)
                .toList().toString();
    }

    private static String userContent(ProviderChatRequest request) {
        return request.messages().stream()
                .filter(message -> message.role() == ProviderChatMessage.Role.USER)
                .map(ProviderChatMessage::content)
                .toList().toString();
    }

    @Test
    void v4DeploymentRequestIsStableAndDoesNotIncludeMergeSha() {
        ObjectMapper mapper = new ObjectMapper();
        CodingHandlerResultService resultService = mock(CodingHandlerResultService.class);
        CodingToolService toolService = mock(CodingToolService.class);
        DeploymentAdapter deployment = mock(DeploymentAdapter.class);
        when(deployment.supportsRepository("backend")).thenReturn(true);
        when(deployment.adapterKey()).thenReturn("local-docker-compose");
        when(deployment.targetKey("backend")).thenReturn("full:backend:spring-app");
        when(deployment.configDigest()).thenReturn(DIFF_DIGEST);
        CodingHandlerStageService service = new CodingHandlerStageService(
                resultService, toolService, mock(CodingModelTurnGuard.class),
                mock(CodingModelTurnService.class), mock(CodingRunnerService.class), deployment,
                mock(ProfileModelBindingService.class),
                mock(GuardrailPathSelectionService.class),
                mock(GuardrailRuleService.class), mapper,
                Clock.fixed(NOW, ZoneOffset.UTC));
        CodingToolService.StageAuthority authority = new CodingToolService.StageAuthority(
                TRACE, 4,
                UUID.fromString("11111111-1111-4111-8111-111111111111"),
                UUID.fromString("22222222-2222-4222-8222-222222222222"),
                UUID.fromString("33333333-3333-4333-8333-333333333333"),
                UUID.fromString("44444444-4444-4444-8444-444444444444"),
                "coding", BASE_SHA, DIFF_DIGEST, DIFF_DIGEST, "coding-v1",
                Set.of("CHAT"), Set.of("coding"), Set.of(), NOW.plusSeconds(60), PROFILE);
        when(toolService.stageAuthority("Bearer worker", JOB, 4)).thenReturn(authority);
        ObjectNode prPayload = mapper.createObjectNode()
                .put("repository", "backend")
                .put("base", "dev")
                .put("prNumber", 42);
        CodingHandlerContract.HandlerResultResponse pullRequest =
                new CodingHandlerContract.HandlerResultResponse(
                        "1.0", UUID.randomUUID(), JOB, TRACE, 1, "coding.pr_complete",
                        CodingHandlerContract.ResultType.PULL_REQUEST, "completed",
                        WORKSPACE, BASE_SHA, DIFF_DIGEST, DIFF_DIGEST, prPayload, NOW);
        when(resultService.aggregate("Bearer worker", JOB, 1)).thenReturn(
                new CodingHandlerContract.AttemptAggregateResponse(
                        "1.0", JOB, TRACE, 1, WORKSPACE,
                        CodingHandlerContract.AttemptStatus.ACTIVE, "deploy it",
                        List.of(pullRequest), List.of(), List.of(), NOW, null));

        CodingHandlerContract.StageExecutionResponse first = service.execute(
                "Bearer worker", JOB, 1, RESULT,
                new CodingHandlerContract.StageExecutionRequest(
                        "1.0", TRACE, 4, 1, "deploy_request",
                        "coding.deploy_request", RESULT));
        UUID replayResult = UUID.fromString("78787878-7878-4787-8787-787878787878");
        CodingHandlerContract.StageExecutionResponse second = service.execute(
                "Bearer worker", JOB, 1, replayResult,
                new CodingHandlerContract.StageExecutionRequest(
                        "1.0", TRACE, 4, 1, "deploy_request",
                        "coding.deploy_request", replayResult));

        assertThat(first.payload().path("deploymentRequestId").asText())
                .isEqualTo(second.payload().path("deploymentRequestId").asText());
        assertThat(first.validationHash()).isEqualTo(second.validationHash());
        assertThat(first.payload().has("mergeSha")).isFalse();
        assertThat(first.payload().path("targetKey").asText())
                .isEqualTo("full:backend:spring-app");
    }

    @Test
    void prCompletionQueuesTheBoundWorkspaceAndPersistsTheExactReceipt() {
        ObjectMapper mapper = new ObjectMapper();
        CodingHandlerResultService resultService = mock(CodingHandlerResultService.class);
        CodingToolService toolService = mock(CodingToolService.class);
        CodingRunnerService runner = mock(CodingRunnerService.class);
        CodingHandlerStageService service = new CodingHandlerStageService(
                resultService, toolService, mock(CodingModelTurnGuard.class),
                mock(CodingModelTurnService.class), runner, mock(DeploymentAdapter.class),
                mock(ProfileModelBindingService.class),
                mock(GuardrailPathSelectionService.class),
                mock(GuardrailRuleService.class), mapper,
                Clock.fixed(NOW, ZoneOffset.UTC));
        CodingToolService.StageAuthority authority = new CodingToolService.StageAuthority(
                TRACE, 4,
                UUID.fromString("11111111-1111-4111-8111-111111111111"),
                UUID.fromString("22222222-2222-4222-8222-222222222222"),
                UUID.fromString("33333333-3333-4333-8333-333333333333"),
                UUID.fromString("44444444-4444-4444-8444-444444444444"),
                "coding", BASE_SHA, DIFF_DIGEST, DIFF_DIGEST, "coding-v1",
                Set.of("CHAT"), Set.of("coding"), Set.of(), NOW.plusSeconds(60), PROFILE);
        when(toolService.stageAuthority("Bearer worker", JOB, 4)).thenReturn(authority);
        CodingHandlerContract.HandlerResultResponse requested =
                new CodingHandlerContract.HandlerResultResponse(
                        "1.0", UUID.randomUUID(), JOB, TRACE, 1, "coding.pr_request",
                        CodingHandlerContract.ResultType.PULL_REQUEST, "requested",
                        WORKSPACE, BASE_SHA, DIFF_DIGEST, DIFF_DIGEST,
                        mapper.createObjectNode(), NOW);
        CodingHandlerContract.ApprovalDecisionSummary githubApproval =
                new CodingHandlerContract.ApprovalDecisionSummary(
                        UUID.randomUUID(), "github_approval",
                        CodingHandlerContract.ApprovalStage.GITHUB, 1,
                        CodingHandlerContract.Decision.APPROVED,
                        BASE_SHA, DIFF_DIGEST, null, UUID.randomUUID(),
                        "SUPER_ADMIN", 4, null, NOW);
        when(resultService.aggregate("Bearer worker", JOB, 1)).thenReturn(
                new CodingHandlerContract.AttemptAggregateResponse(
                        "1.0", JOB, TRACE, 1, WORKSPACE,
                        CodingHandlerContract.AttemptStatus.ACTIVE, "create pr",
                        List.of(requested), List.of(), List.of(), NOW, null));

        assertThatThrownBy(() -> service.execute(
                "Bearer worker", JOB, 1, RESULT,
                new CodingHandlerContract.StageExecutionRequest(
                        "1.0", TRACE, 4, 1, "pr_complete",
                        "coding.pr_complete", RESULT)))
                .isInstanceOf(CodingWorkerException.class)
                .hasMessageContaining("approval");
        verify(runner, never()).enqueue(eq(RESULT), eq("CREATE_PR"), any());

        when(resultService.aggregate("Bearer worker", JOB, 1)).thenReturn(
                new CodingHandlerContract.AttemptAggregateResponse(
                        "1.0", JOB, TRACE, 1, WORKSPACE,
                        CodingHandlerContract.AttemptStatus.ACTIVE, "create pr",
                        List.of(requested), List.of(), List.of(githubApproval), NOW, null));
        when(resultService.jobRequestIdentity(JOB)).thenReturn(
                new CodingHandlerResultService.JobRequestIdentity(
                        "SYSTEM-LLMOPS-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                        "system-llmops-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
        when(resultService.jobRepository(JOB)).thenReturn("backend");
        String headSha = "sha1:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        when(runner.taskOutcome(RESULT, "CREATE_PR")).thenReturn(
                new CodingRunnerService.TaskOutcome("SUCCEEDED", null,
                        mapper.createObjectNode()
                                .put("repository", "backend")
                                .put("base", "dev")
                                .put("head", "system/llmops-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
                                .put("candidateSha", BASE_SHA)
                                .put("headSha", headSha)
                                .put("validationHash", DIFF_DIGEST)
                                .put("prNumber", 42)
                                .put("prUrl", "https://github.example/pr/42")
                                .put("state", "OPEN")
                                .put("authorLogin", "axms-coding[bot]")
                                .put("reused", false)));

        CodingHandlerContract.StageExecutionResponse response = service.execute(
                "Bearer worker", JOB, 1, RESULT,
                new CodingHandlerContract.StageExecutionRequest(
                        "1.0", TRACE, 4, 1, "pr_complete", "coding.pr_complete", RESULT));

        ArgumentCaptor<JsonNode> command = ArgumentCaptor.forClass(JsonNode.class);
        verify(runner).enqueue(eq(RESULT), eq("CREATE_PR"), command.capture());
        assertThat(command.getValue().path("workspaceId").asText())
                .isEqualTo(WORKSPACE.toString());
        assertThat(command.getValue().path("diffDigest").asText())
                .isEqualTo(DIFF_DIGEST);
        assertThat(command.getValue().path("repo").asText()).isEqualTo("backend");
        assertThat(response.payload().path("repository").asText()).isEqualTo("backend");
        assertThat(response.payload().path("headSha").asText()).isEqualTo(headSha);
        assertThat(response.payload().path("validationHash").asText())
                .isEqualTo(DIFF_DIGEST);
        assertThat(response.payload().path("authorLogin").asText())
                .isEqualTo("axms-coding[bot]");
        assertThat(response.payload().path("reused").asBoolean()).isFalse();
        assertThat(response.payload().path("prNumber").asInt()).isEqualTo(42);
    }

    /* The frontend has its own fixed local target, so its receipt advertises deployment the
     * same way the backend's does; the graph then routes it to the deployment request. */
    @Test
    void prCompletionPublishesToTheRepositoryTheJobWorksIn() {
        PullRequestFixture fixture = pullRequestFixture("frontend");

        CodingHandlerContract.StageExecutionResponse response = fixture.service().execute(
                "Bearer worker", JOB, 1, RESULT,
                new CodingHandlerContract.StageExecutionRequest(
                        "1.0", TRACE, 4, 1, "pr_complete", "coding.pr_complete", RESULT));

        ArgumentCaptor<JsonNode> command = ArgumentCaptor.forClass(JsonNode.class);
        verify(fixture.runner()).enqueue(eq(RESULT), eq("CREATE_PR"), command.capture());
        assertThat(command.getValue().path("repo").asText()).isEqualTo("frontend");
        assertThat(response.payload().path("repository").asText()).isEqualTo("frontend");
        assertThat(response.payload().path("deploymentSupported").isBoolean()).isTrue();
        assertThat(response.payload().path("deploymentSupported").asBoolean()).isTrue();
        assertThat(response.resultPort()).isEqualTo("completed");
    }

    @Test
    void backendPrCompletionAdvertisesTheServerDeploymentCapability() {
        PullRequestFixture fixture = pullRequestFixture("backend");
        CodingHandlerContract.StageExecutionResponse response = fixture.service().execute(
                "Bearer worker", JOB, 1, RESULT,
                new CodingHandlerContract.StageExecutionRequest(
                        "1.0", TRACE, 4, 1, "pr_complete", "coding.pr_complete", RESULT));
        assertThat(response.payload().path("deploymentSupported").asBoolean()).isTrue();
        assertThat(response.resultPort()).isEqualTo("completed");
    }

    @Test
    void prCompletionRefusesAReceiptFromAnotherRepository() {
        PullRequestFixture fixture = pullRequestFixture("frontend", "backend");

        assertThatThrownBy(() -> fixture.service().execute(
                "Bearer worker", JOB, 1, RESULT,
                new CodingHandlerContract.StageExecutionRequest(
                        "1.0", TRACE, 4, 1, "pr_complete", "coding.pr_complete", RESULT)))
                .isInstanceOf(CodingWorkerException.class)
                .hasMessageContaining("receipt");
    }

    @Test
    void frontendPullRequestCannotCreateABackendDeploymentRequest() {
        ObjectMapper mapper = new ObjectMapper();
        CodingHandlerResultService resultService = mock(CodingHandlerResultService.class);
        CodingToolService toolService = mock(CodingToolService.class);
        DeploymentAdapter deployment = mock(DeploymentAdapter.class);
        CodingHandlerStageService service = new CodingHandlerStageService(
                resultService, toolService, mock(CodingModelTurnGuard.class),
                mock(CodingModelTurnService.class), mock(CodingRunnerService.class), deployment,
                mock(ProfileModelBindingService.class),
                mock(GuardrailPathSelectionService.class),
                mock(GuardrailRuleService.class), mapper,
                Clock.fixed(NOW, ZoneOffset.UTC));
        CodingToolService.StageAuthority authority = new CodingToolService.StageAuthority(
                TRACE, 4,
                UUID.fromString("11111111-1111-4111-8111-111111111111"),
                UUID.fromString("22222222-2222-4222-8222-222222222222"),
                UUID.fromString("33333333-3333-4333-8333-333333333333"),
                UUID.fromString("44444444-4444-4444-8444-444444444444"),
                "coding", BASE_SHA, DIFF_DIGEST, DIFF_DIGEST, "coding-v1",
                Set.of("CHAT"), Set.of("coding"), Set.of(), NOW.plusSeconds(60), PROFILE);
        when(toolService.stageAuthority("Bearer worker", JOB, 4)).thenReturn(authority);
        CodingHandlerContract.HandlerResultResponse pullRequest =
                new CodingHandlerContract.HandlerResultResponse(
                        "1.0", UUID.randomUUID(), JOB, TRACE, 1, "coding.pr_complete",
                        CodingHandlerContract.ResultType.PULL_REQUEST, "completed",
                        WORKSPACE, BASE_SHA, DIFF_DIGEST, DIFF_DIGEST,
                        mapper.createObjectNode()
                                .put("repository", "frontend")
                                .put("base", "dev")
                                .put("head", "system/llmops-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
                                .put("candidateSha", BASE_SHA)
                                .put("validationHash", DIFF_DIGEST)
                                .put("headSha",
                                        "sha1:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
                                .put("prNumber", 42),
                        NOW);
        when(resultService.aggregate("Bearer worker", JOB, 1)).thenReturn(
                new CodingHandlerContract.AttemptAggregateResponse(
                        "1.0", JOB, TRACE, 1, WORKSPACE,
                        CodingHandlerContract.AttemptStatus.ACTIVE, "deploy frontend",
                        List.of(pullRequest), List.of(), List.of(), NOW, null));

        assertThatThrownBy(() -> service.execute(
                "Bearer worker", JOB, 1, RESULT,
                new CodingHandlerContract.StageExecutionRequest(
                        "1.0", TRACE, 4, 1, "deploy_request",
                        "coding.deploy_request", RESULT)))
                .isInstanceOf(CodingWorkerException.class)
                .hasMessageContaining("server deployment target");

        verify(deployment, never()).deploy(any(), any());
    }

    @Test
    void prCompletionTakesTheOverlappingPreviewDownBeforeExporting() {
        PullRequestFixture fixture = pullRequestFixture("frontend");

        fixture.service().execute(
                "Bearer worker", JOB, 1, RESULT,
                new CodingHandlerContract.StageExecutionRequest(
                        "1.0", TRACE, 4, 1, "pr_complete", "coding.pr_complete", RESULT));

        // Order matters: the runner claims one pending row at a time, so the preview has to be
        // queued down first or the export still meets the folder the preview holds.
        InOrder order = inOrder(fixture.runner());
        order.verify(fixture.runner())
                .enqueue(any(UUID.class), eq("PREVIEW_DOWN"), any());
        order.verify(fixture.runner()).enqueue(eq(RESULT), eq("CREATE_PR"), any());
    }

    @Test
    void prBodyCarriesEverythingTheReviewerHasToCheck() {
        PullRequestFixture fixture = pullRequestFixture("frontend");

        fixture.service().execute(
                "Bearer worker", JOB, 1, RESULT,
                new CodingHandlerContract.StageExecutionRequest(
                        "1.0", TRACE, 4, 1, "pr_complete", "coding.pr_complete", RESULT));

        ArgumentCaptor<JsonNode> command = ArgumentCaptor.forClass(JsonNode.class);
        verify(fixture.runner()).enqueue(eq(RESULT), eq("CREATE_PR"), command.capture());
        String body = command.getValue().path("body").asText();
        assertThat(body)
                .contains("## 결과", "## 변경", "## 검증", "## 연결·영향", "## 확인")
                .contains("사업 소개 보기 버튼 옆에 버튼을 하나 만들어줘")
                .contains("사업 소개 옆에 버튼을 하나 추가합니다.")
                .contains("src/features/site/PublicSite.tsx")
                .contains("1개 · 12줄")
                .contains("git-diff-check")
                .contains("충족 · 버튼이 추가된다")
                .contains("SCOPE · 승인 · GENERAL_ADMIN")
                .contains("GITHUB · 승인 · SUPER_ADMIN")
                .contains("1번째 시도")
                .contains(BASE_SHA);
    }

    @Test
    void prBodySaysWhatWasNeverRecordedInsteadOfLeavingItBlank() {
        PullRequestFixture fixture = pullRequestFixture("backend");
        // A Job whose earlier stages left no payload still has to produce a readable body.
        when(fixture.resultService().aggregate("Bearer worker", JOB, 1)).thenReturn(
                new CodingHandlerContract.AttemptAggregateResponse(
                        "1.0", JOB, TRACE, 1, WORKSPACE,
                        CodingHandlerContract.AttemptStatus.ACTIVE, "요청문",
                        List.of(fixture.requested()), List.of(),
                        List.of(fixture.githubApproval()), NOW, null));

        fixture.service().execute(
                "Bearer worker", JOB, 1, RESULT,
                new CodingHandlerContract.StageExecutionRequest(
                        "1.0", TRACE, 4, 1, "pr_complete", "coding.pr_complete", RESULT));

        ArgumentCaptor<JsonNode> command = ArgumentCaptor.forClass(JsonNode.class);
        verify(fixture.runner()).enqueue(eq(RESULT), eq("CREATE_PR"), command.capture());
        String body = command.getValue().path("body").asText();
        assertThat(body)
                .contains("- 계획: 기록 없음")
                .contains("- 바뀐 파일: 기록 없음")
                .contains("- 검토 판정: 기록 없음");
    }

    private record PullRequestFixture(
            CodingHandlerStageService service,
            CodingRunnerService runner,
            CodingHandlerResultService resultService,
            CodingHandlerContract.HandlerResultResponse requested,
            CodingHandlerContract.ApprovalDecisionSummary githubApproval) { }


    @Test
    void prCompletionWaitsForTheRunnerInsteadOfSpendingTheJobsAttempts() {
        PullRequestFixture fixture = pullRequestFixture("frontend");
        // Taking the preview down and exporting the workspace took forty seconds in the
        // measured run. Failing over each poll would charge the Job a worker attempt, and it
        // only has three of those for its whole life.
        when(fixture.runner().taskOutcome(RESULT, "CREATE_PR")).thenReturn(
                new CodingRunnerService.TaskOutcome("PENDING", null, null),
                new CodingRunnerService.TaskOutcome("RUNNING", null, null),
                new CodingRunnerService.TaskOutcome("SUCCEEDED", null,
                        new ObjectMapper().createObjectNode()
                                .put("repository", "frontend")
                                .put("base", "dev")
                                .put("head",
                                        "system/llmops-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
                                .put("candidateSha", BASE_SHA)
                                .put("headSha",
                                        "sha1:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
                                .put("validationHash", DIFF_DIGEST)
                                .put("prNumber", 45)
                                .put("prUrl", "https://github.example/pr/45")
                                .put("state", "OPEN")
                                .put("authorLogin", "axms-coding[bot]")
                                .put("reused", true)));

        CodingHandlerContract.StageExecutionResponse response = fixture.service().execute(
                "Bearer worker", JOB, 1, RESULT,
                new CodingHandlerContract.StageExecutionRequest(
                        "1.0", TRACE, 4, 1, "pr_complete", "coding.pr_complete", RESULT));

        assertThat(response.payload().path("prNumber").asInt()).isEqualTo(45);
        assertThat(response.payload().path("prUrl").asText())
                .isEqualTo("https://github.example/pr/45");
        verify(fixture.runner(), times(3)).taskOutcome(RESULT, "CREATE_PR");
    }

    @Test
    void prCompletionStillGivesUpWhenTheRunnerNeverFinishes() {
        PullRequestFixture fixture =
                pullRequestFixture("frontend", "frontend", 3, Duration.ofMillis(1));
        when(fixture.runner().taskOutcome(RESULT, "CREATE_PR")).thenReturn(
                new CodingRunnerService.TaskOutcome("PENDING", null, null));

        assertThatThrownBy(() -> fixture.service().execute(
                "Bearer worker", JOB, 1, RESULT,
                new CodingHandlerContract.StageExecutionRequest(
                        "1.0", TRACE, 4, 1, "pr_complete", "coding.pr_complete", RESULT)))
                .isInstanceOf(CodingWorkerException.class)
                .extracting(failure -> ((CodingWorkerException) failure).code())
                .isEqualTo("RUNNER_TASK_PENDING");
        // One look before the wait, then one per poll: the outer net is still there.
        verify(fixture.runner(), times(4)).taskOutcome(RESULT, "CREATE_PR");
    }

    private PullRequestFixture pullRequestFixture(String repository) {
        return pullRequestFixture(repository, repository);
    }

    /**
     * A Job approved at GITHUB, ready for {@code coding.pr_complete}.
     *
     * <p>{@code jobRepository} and the runner receipt are separate arguments on purpose: the
     * stage has to notice when the receipt names a repository the Job does not work in.
     */
    private PullRequestFixture pullRequestFixture(
            String repository, String receiptRepository) {
        return pullRequestFixture(
                repository, receiptRepository, 120, Duration.ofMillis(500));
    }

    /** The last two arguments shorten the runner wait so a test need not sit through it. */
    private PullRequestFixture pullRequestFixture(
            String repository,
            String receiptRepository,
            int maxRunnerPolls,
            Duration runnerPollInterval) {
        ObjectMapper mapper = new ObjectMapper();
        CodingHandlerResultService resultService = mock(CodingHandlerResultService.class);
        CodingToolService toolService = mock(CodingToolService.class);
        CodingRunnerService runner = mock(CodingRunnerService.class);
        CodingHandlerStageService service = new CodingHandlerStageService(
                resultService, toolService, mock(CodingModelTurnGuard.class),
                mock(CodingModelTurnService.class), runner,
                new org.urizo.axmodulestudio.backend.coding.integration.LocalDockerComposeDeploymentAdapter(runner),
                mock(ProfileModelBindingService.class),
                mock(GuardrailPathSelectionService.class),
                mock(GuardrailRuleService.class), mapper,
                Clock.fixed(NOW, ZoneOffset.UTC), maxRunnerPolls, runnerPollInterval);
        CodingToolService.StageAuthority authority = new CodingToolService.StageAuthority(
                TRACE, 4,
                UUID.fromString("11111111-1111-4111-8111-111111111111"),
                UUID.fromString("22222222-2222-4222-8222-222222222222"),
                UUID.fromString("33333333-3333-4333-8333-333333333333"),
                UUID.fromString("44444444-4444-4444-8444-444444444444"),
                "coding", BASE_SHA, DIFF_DIGEST, DIFF_DIGEST, "coding-v1",
                Set.of("CHAT"), Set.of("coding"), Set.of(), NOW.plusSeconds(60), PROFILE);
        when(toolService.stageAuthority("Bearer worker", JOB, 4)).thenReturn(authority);
        CodingHandlerContract.HandlerResultResponse requested =
                new CodingHandlerContract.HandlerResultResponse(
                        "1.0", UUID.randomUUID(), JOB, TRACE, 1, "coding.pr_request",
                        CodingHandlerContract.ResultType.PULL_REQUEST, "requested",
                        WORKSPACE, BASE_SHA, DIFF_DIGEST, DIFF_DIGEST,
                        mapper.createObjectNode(), NOW);
        CodingHandlerContract.ApprovalDecisionSummary githubApproval =
                new CodingHandlerContract.ApprovalDecisionSummary(
                        UUID.randomUUID(), "github_approval",
                        CodingHandlerContract.ApprovalStage.GITHUB, 1,
                        CodingHandlerContract.Decision.APPROVED,
                        BASE_SHA, DIFF_DIGEST, null, UUID.randomUUID(),
                        "SUPER_ADMIN", 4, null, NOW);
        CodingHandlerContract.HandlerResultResponse analyzed =
                new CodingHandlerContract.HandlerResultResponse(
                        "1.0", UUID.randomUUID(), JOB, TRACE, 1, "coding.analyze",
                        CodingHandlerContract.ResultType.ANALYSIS, "feasible",
                        WORKSPACE, null, null, null,
                        mapper.createObjectNode()
                                .put("planSummary", "사업 소개 옆에 버튼을 하나 추가합니다."),
                        NOW);
        ObjectNode reviewPayload = mapper.createObjectNode();
        reviewPayload.putArray("criteriaResults")
                .addObject().put("criterion", "버튼이 추가된다").put("met", true);
        CodingHandlerContract.HandlerResultResponse reviewed =
                new CodingHandlerContract.HandlerResultResponse(
                        "1.0", UUID.randomUUID(), JOB, TRACE, 1, "coding.review",
                        CodingHandlerContract.ResultType.REVIEW, "passed",
                        WORKSPACE, BASE_SHA, DIFF_DIGEST, DIFF_DIGEST, reviewPayload, NOW);
        ObjectNode previewPayload = mapper.createObjectNode();
        previewPayload.putArray("changedPaths").add("src/features/site/PublicSite.tsx");
        previewPayload.put("changedLines", 12).put("checkProfile", "git-diff-check");
        CodingHandlerContract.HandlerResultResponse previewed =
                new CodingHandlerContract.HandlerResultResponse(
                        "1.0", UUID.randomUUID(), JOB, TRACE, 1, "coding.preview",
                        CodingHandlerContract.ResultType.DIFF, "ready",
                        WORKSPACE, BASE_SHA, DIFF_DIGEST, DIFF_DIGEST, previewPayload, NOW);
        CodingHandlerContract.ApprovalDecisionSummary scopeApproval =
                new CodingHandlerContract.ApprovalDecisionSummary(
                        UUID.randomUUID(), "scope_approval",
                        CodingHandlerContract.ApprovalStage.SCOPE, 1,
                        CodingHandlerContract.Decision.APPROVED,
                        null, null, null, UUID.randomUUID(),
                        "GENERAL_ADMIN", 2, null, NOW);
        when(resultService.aggregate("Bearer worker", JOB, 1)).thenReturn(
                new CodingHandlerContract.AttemptAggregateResponse(
                        "1.0", JOB, TRACE, 1, WORKSPACE,
                        CodingHandlerContract.AttemptStatus.ACTIVE,
                        "사업 소개 보기 버튼 옆에 버튼을 하나 만들어줘",
                        List.of(analyzed, reviewed, previewed, requested), List.of(),
                        List.of(scopeApproval, githubApproval), NOW, null));
        when(resultService.jobRequestIdentity(JOB)).thenReturn(
                new CodingHandlerResultService.JobRequestIdentity(
                        "SYSTEM-LLMOPS-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                        "system-llmops-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
        when(resultService.jobRepository(JOB)).thenReturn(repository);
        when(runner.taskOutcome(RESULT, "CREATE_PR")).thenReturn(
                new CodingRunnerService.TaskOutcome("SUCCEEDED", null,
                        mapper.createObjectNode()
                                .put("repository", receiptRepository)
                                .put("base", "dev")
                                .put("head", "system/llmops-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
                                .put("candidateSha", BASE_SHA)
                                .put("headSha", "sha1:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
                                .put("validationHash", DIFF_DIGEST)
                                .put("prNumber", 42)
                                .put("prUrl", "https://github.example/pr/42")
                                .put("state", "OPEN")
                                .put("authorLogin", "axms-coding[bot]")
                                .put("reused", false)));
        return new PullRequestFixture(
                service, runner, resultService, requested, githubApproval);
    }

    @Test
    void mergeCheckAndDeployStopBeforeSideEffectsWithoutDeployApproval() {
        ObjectMapper mapper = new ObjectMapper();
        CodingHandlerResultService resultService = mock(CodingHandlerResultService.class);
        CodingToolService toolService = mock(CodingToolService.class);
        CodingRunnerService runner = mock(CodingRunnerService.class);
        DeploymentAdapter deployment = mock(DeploymentAdapter.class);
        when(deployment.supportsRepository("backend")).thenReturn(true);
        CodingHandlerStageService service = new CodingHandlerStageService(
                resultService, toolService, mock(CodingModelTurnGuard.class),
                mock(CodingModelTurnService.class), runner, deployment,
                mock(ProfileModelBindingService.class),
                mock(GuardrailPathSelectionService.class),
                mock(GuardrailRuleService.class), mapper,
                Clock.fixed(NOW, ZoneOffset.UTC));
        CodingToolService.StageAuthority authority = new CodingToolService.StageAuthority(
                TRACE, 4,
                UUID.fromString("11111111-1111-4111-8111-111111111111"),
                UUID.fromString("22222222-2222-4222-8222-222222222222"),
                UUID.fromString("33333333-3333-4333-8333-333333333333"),
                UUID.fromString("44444444-4444-4444-8444-444444444444"),
                "coding", BASE_SHA, DIFF_DIGEST, DIFF_DIGEST, "coding-v1",
                Set.of("CHAT"), Set.of("coding"), Set.of(), NOW.plusSeconds(60), PROFILE);
        when(toolService.stageAuthority("Bearer worker", JOB, 4)).thenReturn(authority);
        ObjectNode prPayload = mapper.createObjectNode()
                .put("repository", "backend")
                .put("base", "dev")
                .put("head", "system/llmops-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
                .put("headSha", "sha1:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
                .put("prNumber", 42)
                .put("candidateSha", BASE_SHA);
        CodingHandlerContract.HandlerResultResponse pullRequest =
                new CodingHandlerContract.HandlerResultResponse(
                        "1.0", UUID.randomUUID(), JOB, TRACE, 1, "coding.pr_complete",
                        CodingHandlerContract.ResultType.PULL_REQUEST, "completed",
                        WORKSPACE, BASE_SHA, DIFF_DIGEST, DIFF_DIGEST, prPayload, NOW);
        String deployHash =
                "sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd";
        ObjectNode deployPayload = mapper.createObjectNode()
                .put("deploymentRequestId", "81818181-8181-4181-8181-818181818181")
                .put("repository", "backend")
                .put("prNumber", 42)
                .put("candidateSha", BASE_SHA);
        CodingHandlerContract.HandlerResultResponse deployRequest =
                new CodingHandlerContract.HandlerResultResponse(
                        "1.0", UUID.randomUUID(), JOB, TRACE, 1, "coding.deploy_request",
                        CodingHandlerContract.ResultType.DEPLOY_REQUEST, "recorded",
                        WORKSPACE, BASE_SHA, DIFF_DIGEST, deployHash, deployPayload, NOW);
        CodingHandlerContract.HandlerResultResponse merged =
                new CodingHandlerContract.HandlerResultResponse(
                        "1.0", UUID.randomUUID(), JOB, TRACE, 1,
                        "coding.dev_merge_check", CodingHandlerContract.ResultType.DEV_MERGE,
                        "merged", WORKSPACE, BASE_SHA, DIFF_DIGEST, DIFF_DIGEST,
                        mapper.createObjectNode().put(
                                "mergeSha", "sha1:cccccccccccccccccccccccccccccccccccccccc"),
                        NOW);
        when(resultService.aggregate("Bearer worker", JOB, 1)).thenReturn(
                new CodingHandlerContract.AttemptAggregateResponse(
                        "1.0", JOB, TRACE, 1, WORKSPACE,
                        CodingHandlerContract.AttemptStatus.ACTIVE, "deploy",
                        List.of(pullRequest, deployRequest, merged),
                        List.of(), List.of(), NOW, null));

        assertThatThrownBy(() -> service.execute(
                "Bearer worker", JOB, 1, RESULT,
                new CodingHandlerContract.StageExecutionRequest(
                        "1.0", TRACE, 4, 1, "dev_merge_check",
                        "coding.dev_merge_check", RESULT)))
                .isInstanceOf(CodingWorkerException.class)
                .hasMessageContaining("approval");
        UUID deployResult = UUID.fromString("79797979-7979-4797-8797-797979797979");
        assertThatThrownBy(() -> service.execute(
                "Bearer worker", JOB, 1, deployResult,
                new CodingHandlerContract.StageExecutionRequest(
                        "1.0", TRACE, 4, 1, "deploy", "coding.deploy", deployResult)))
                .isInstanceOf(CodingWorkerException.class)
                .hasMessageContaining("approval");

        verify(runner, never()).enqueue(eq(RESULT), eq("CHECK_DEV_MERGE"), any());
        verify(deployment, never()).deploy(any(), any());
    }

    @Test
    void devMergeCheckWaitsForTheRunnerInsteadOfSpendingTheJobsAttempts() {
        // Job d7414a3c pressed DEPLOY twice as the graph intends (not merged, then merged)
        // and each press cost a worker attempt because this stage failed over at once.
        DeployFixture fixture = deployFixture(false, 120, Duration.ofMillis(1));
        ObjectMapper mapper = new ObjectMapper();
        when(fixture.runner().taskOutcome(RESULT, "CHECK_DEV_MERGE")).thenReturn(
                new CodingRunnerService.TaskOutcome("PENDING", null, null),
                new CodingRunnerService.TaskOutcome("RUNNING", null, null),
                new CodingRunnerService.TaskOutcome("SUCCEEDED", null,
                        mapper.createObjectNode()
                                .put("status", "NOT_MERGED")
                                .put("candidateSha", BASE_SHA)
                                .put("head", "system/llmops-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
                                .put("headSha",
                                        "sha1:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")));

        CodingHandlerContract.StageExecutionResponse response = fixture.service().execute(
                "Bearer worker", JOB, 1, RESULT,
                new CodingHandlerContract.StageExecutionRequest(
                        "1.0", TRACE, 4, 1, "dev_merge_check",
                        "coding.dev_merge_check", RESULT));

        assertThat(response.resultPort()).isEqualTo("not_merged");
        assertThat(response.payload().path("status").asText()).isEqualTo("NOT_MERGED");
        verify(fixture.runner(), times(3)).taskOutcome(RESULT, "CHECK_DEV_MERGE");
    }

    @Test
    void devMergeCheckStillGivesUpWhenTheRunnerNeverFinishes() {
        DeployFixture fixture = deployFixture(false, 3, Duration.ofMillis(1));
        when(fixture.runner().taskOutcome(RESULT, "CHECK_DEV_MERGE")).thenReturn(
                new CodingRunnerService.TaskOutcome("PENDING", null, null));

        assertThatThrownBy(() -> fixture.service().execute(
                "Bearer worker", JOB, 1, RESULT,
                new CodingHandlerContract.StageExecutionRequest(
                        "1.0", TRACE, 4, 1, "dev_merge_check",
                        "coding.dev_merge_check", RESULT)))
                .isInstanceOf(CodingWorkerException.class)
                .extracting(failure -> ((CodingWorkerException) failure).code())
                .isEqualTo("RUNNER_TASK_PENDING");
        // One look before the wait, then one per poll: the outer net is still there.
        verify(fixture.runner(), times(4)).taskOutcome(RESULT, "CHECK_DEV_MERGE");
    }

    @Test
    void deploymentWaitsForTheAdapterInsteadOfSpendingTheJobsAttempts() {
        DeployFixture fixture = deployFixture(true, 120, Duration.ofMillis(1));
        ObjectMapper mapper = new ObjectMapper();
        when(fixture.deployment().deploy(any(), any())).thenReturn(
                new DeploymentAdapter.DeploymentOutcome(
                        DeploymentAdapter.Status.PENDING, null, null),
                new DeploymentAdapter.DeploymentOutcome(
                        DeploymentAdapter.Status.PENDING, null, null),
                new DeploymentAdapter.DeploymentOutcome(
                        DeploymentAdapter.Status.COMPLETED,
                        mapper.createObjectNode().put("status", "COMPLETED"), null));

        CodingHandlerContract.StageExecutionResponse response = fixture.service().execute(
                "Bearer worker", JOB, 1, RESULT,
                new CodingHandlerContract.StageExecutionRequest(
                        "1.0", TRACE, 4, 1, "deploy", "coding.deploy", RESULT));

        assertThat(response.resultPort()).isEqualTo("completed");
        assertThat(response.payload().path("status").asText()).isEqualTo("COMPLETED");
        // Every look uses the same execution id, so the adapter reports on one deployment
        // rather than starting another.
        ArgumentCaptor<UUID> executionIds = ArgumentCaptor.forClass(UUID.class);
        verify(fixture.deployment(), times(3)).deploy(executionIds.capture(), any());
        assertThat(executionIds.getAllValues()).containsOnly(executionIds.getAllValues().get(0));
    }

    @Test
    void deploymentStillGivesUpWhenTheAdapterNeverFinishes() {
        DeployFixture fixture = deployFixture(true, 3, Duration.ofMillis(1));
        when(fixture.deployment().deploy(any(), any())).thenReturn(
                new DeploymentAdapter.DeploymentOutcome(
                        DeploymentAdapter.Status.PENDING, null, null));

        assertThatThrownBy(() -> fixture.service().execute(
                "Bearer worker", JOB, 1, RESULT,
                new CodingHandlerContract.StageExecutionRequest(
                        "1.0", TRACE, 4, 1, "deploy", "coding.deploy", RESULT)))
                .isInstanceOf(CodingWorkerException.class)
                .extracting(failure -> ((CodingWorkerException) failure).code())
                .isEqualTo("RUNNER_TASK_PENDING");
        verify(fixture.deployment(), times(4)).deploy(any(), any());
    }

    private record DeployFixture(
            CodingHandlerStageService service,
            CodingRunnerService runner,
            DeploymentAdapter deployment) { }

    /**
     * A frontend Job approved at DEPLOY, ready for {@code coding.dev_merge_check}; with
     * {@code merged} it also carries the merged receipt {@code coding.deploy} needs.
     */
    private DeployFixture deployFixture(
            boolean merged, int maxRunnerPolls, Duration runnerPollInterval) {
        ObjectMapper mapper = new ObjectMapper();
        CodingHandlerResultService resultService = mock(CodingHandlerResultService.class);
        CodingToolService toolService = mock(CodingToolService.class);
        CodingRunnerService runner = mock(CodingRunnerService.class);
        DeploymentAdapter deployment = mock(DeploymentAdapter.class);
        String configDigest =
                "sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee";
        when(deployment.supportsRepository("frontend")).thenReturn(true);
        when(deployment.adapterKey()).thenReturn("local-docker-compose");
        when(deployment.targetKey("frontend")).thenReturn("full:frontend:frontend");
        when(deployment.configDigest()).thenReturn(configDigest);
        CodingHandlerStageService service = new CodingHandlerStageService(
                resultService, toolService, mock(CodingModelTurnGuard.class),
                mock(CodingModelTurnService.class), runner, deployment,
                mock(ProfileModelBindingService.class),
                mock(GuardrailPathSelectionService.class),
                mock(GuardrailRuleService.class), mapper,
                Clock.fixed(NOW, ZoneOffset.UTC), maxRunnerPolls, runnerPollInterval);
        CodingToolService.StageAuthority authority = new CodingToolService.StageAuthority(
                TRACE, 4,
                UUID.fromString("11111111-1111-4111-8111-111111111111"),
                UUID.fromString("22222222-2222-4222-8222-222222222222"),
                UUID.fromString("33333333-3333-4333-8333-333333333333"),
                UUID.fromString("44444444-4444-4444-8444-444444444444"),
                "coding", BASE_SHA, DIFF_DIGEST, DIFF_DIGEST, "coding-v1",
                Set.of("CHAT"), Set.of("coding"), Set.of(), NOW.plusSeconds(60), PROFILE);
        when(toolService.stageAuthority("Bearer worker", JOB, 4)).thenReturn(authority);
        CodingHandlerContract.HandlerResultResponse pullRequest =
                new CodingHandlerContract.HandlerResultResponse(
                        "1.0", UUID.randomUUID(), JOB, TRACE, 1, "coding.pr_complete",
                        CodingHandlerContract.ResultType.PULL_REQUEST, "completed",
                        WORKSPACE, BASE_SHA, DIFF_DIGEST, DIFF_DIGEST,
                        mapper.createObjectNode()
                                .put("repository", "frontend")
                                .put("base", "dev")
                                .put("head", "system/llmops-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
                                .put("headSha",
                                        "sha1:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
                                .put("prNumber", 42)
                                .put("candidateSha", BASE_SHA),
                        NOW);
        String deployHash =
                "sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd";
        CodingHandlerContract.HandlerResultResponse deployRequest =
                new CodingHandlerContract.HandlerResultResponse(
                        "1.0", UUID.randomUUID(), JOB, TRACE, 1, "coding.deploy_request",
                        CodingHandlerContract.ResultType.DEPLOY_REQUEST, "recorded",
                        WORKSPACE, BASE_SHA, DIFF_DIGEST, deployHash,
                        mapper.createObjectNode()
                                .put("deploymentRequestId",
                                        "81818181-8181-4181-8181-818181818181")
                                .put("repository", "frontend")
                                .put("prNumber", 42)
                                .put("candidateSha", BASE_SHA)
                                .put("adapterKey", "local-docker-compose")
                                .put("targetKey", "full:frontend:frontend")
                                .put("configDigest", configDigest),
                        NOW);
        CodingHandlerContract.ApprovalDecisionSummary deployApproval =
                new CodingHandlerContract.ApprovalDecisionSummary(
                        UUID.randomUUID(), "deploy_approval",
                        CodingHandlerContract.ApprovalStage.DEPLOY, 1,
                        CodingHandlerContract.Decision.APPROVED,
                        BASE_SHA, deployHash, null, UUID.randomUUID(),
                        "SUPER_ADMIN", 6, null, NOW);
        List<CodingHandlerContract.HandlerResultResponse> results =
                new java.util.ArrayList<>(List.of(pullRequest, deployRequest));
        if (merged) {
            results.add(new CodingHandlerContract.HandlerResultResponse(
                    "1.0", UUID.randomUUID(), JOB, TRACE, 1,
                    "coding.dev_merge_check", CodingHandlerContract.ResultType.DEV_MERGE,
                    "merged", WORKSPACE, BASE_SHA, DIFF_DIGEST, DIFF_DIGEST,
                    mapper.createObjectNode().put(
                            "mergeSha", "sha1:cccccccccccccccccccccccccccccccccccccccc"),
                    NOW));
        }
        when(resultService.aggregate("Bearer worker", JOB, 1)).thenReturn(
                new CodingHandlerContract.AttemptAggregateResponse(
                        "1.0", JOB, TRACE, 1, WORKSPACE,
                        CodingHandlerContract.AttemptStatus.ACTIVE, "deploy frontend",
                        List.copyOf(results), List.of(), List.of(deployApproval), NOW, null));
        return new DeployFixture(service, runner, deployment);
    }

    private static ProfileToolBindingPolicy bindingPolicy(ObjectMapper mapper) {
        try {
            JsonNode snapshot = mapper.readTree(Files.readString(Path.of(
                    "contracts/fixtures/orchestration/llm-ops-coding-handler.snapshot.valid.json")));
            return ProfileToolBindingPolicy.decode(
                    snapshot, CodingToolService.CODING_TOOL_SCHEMA_DIGESTS.keySet());
        }
        catch (java.io.IOException failure) {
            throw new AssertionError(failure);
        }
    }
}

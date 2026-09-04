package org.urizo.axmodulestudio.backend.coding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import org.mockito.ArgumentCaptor;
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
            CodingHandlerContract.StageExecutionRequest request) { }

    private static StageFixture stageFixture(ObjectMapper mapper) {
        return stageFixture(mapper, bindingPolicy(mapper));
    }

    private static StageFixture stageFixture(
            ObjectMapper mapper, ProfileToolBindingPolicy toolBindings) {
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
                gateway, mapper, clock, false);
        ProfileModelBindingService anyBindings = mock(ProfileModelBindingService.class);
        // The profile always resolves to a binding in production: resolve either returns
        // a list or throws, so the stage never hands the turn service a null selection.
        when(anyBindings.resolve(any(), any(), any(), any())).thenReturn(List.of(registration));
        CodingHandlerStageService service = new CodingHandlerStageService(
                resultService, toolService, guard, modelService,
                mock(CodingRunnerService.class), mock(DeploymentAdapter.class), anyBindings,
                mock(GuardrailPathSelectionService.class),
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
                        "Implement the approved change.",
                        List.of(), List.of(), List.of(), NOW, null);
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
                        "1.0", TRACE, 4, 1, "coding.code", RESULT));
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
        return new ProviderChatResponse(
                ModelProvider.GOOGLE_GENAI, "coding-test-model",
                "",
                List.of(new ProviderChatMessage.ToolCall(callId, tool, arguments)),
                10, 5, Duration.ofMillis(10));
    }

    private static ProviderChatResponse terminalReply() {
        return new ProviderChatResponse(
                ModelProvider.GOOGLE_GENAI, "coding-test-model",
                "{\"port\":\"completed\",\"payload\":{\"summary\":\"done\"}}",
                12, 6, Duration.ofMillis(10));
    }

    @Test
    void handsAToolRefusalBackToTheModelInsteadOfEndingTheJob() {
        ObjectMapper mapper = new ObjectMapper();
        StageFixture fixture = stageFixture(mapper);
        // The refused call never ran, so the loop must survive it: the refusal reason is
        // handed back and the corrected exchange finishes the stage.
        when(fixture.toolService().submitForNode(
                eq("Bearer worker"), any(), eq("code")))
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
        assertThat(routed.getAllValues().get(1).prompt())
                .contains("apply_patch succeeded")
                .contains("run_check")
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

    /** Wiring for the single-call analyze stage; the gateway answer is per-test. */
    private record AnalyzeFixture(
            CodingHandlerStageService service,
            ProviderChatGatewayPort gateway,
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
        return new AnalyzeFixture(service, gateway, selections);
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
        assertThat(replay).isEqualTo(response);

        ArgumentCaptor<JsonNode> toolRequest = ArgumentCaptor.forClass(JsonNode.class);
        verify(toolService).submitForNode(
                eq("Bearer worker"), toolRequest.capture(), eq("code"));
        assertThat(toolRequest.getValue().path("tool").path("name").asText())
                .isEqualTo("read_diff");
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
    }

    @Test
    void v4DeploymentRequestIsStableAndDoesNotIncludeMergeSha() {
        ObjectMapper mapper = new ObjectMapper();
        CodingHandlerResultService resultService = mock(CodingHandlerResultService.class);
        CodingToolService toolService = mock(CodingToolService.class);
        DeploymentAdapter deployment = mock(DeploymentAdapter.class);
        when(deployment.adapterKey()).thenReturn("local-docker-compose");
        when(deployment.targetKey()).thenReturn("full:backend:spring-app");
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
        String headSha = "sha1:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        when(runner.taskOutcome(RESULT, "CREATE_PR")).thenReturn(
                new CodingRunnerService.TaskOutcome("SUCCEEDED", null,
                        mapper.createObjectNode()
                                .put("repository", "backend")
                                .put("base", "dev")
                                .put("head", "system/llmops-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
                                .put("candidateSha", BASE_SHA)
                                .put("headSha", headSha)
                                .put("prNumber", 42)
                                .put("prUrl", "https://github.example/pr/42")
                                .put("state", "OPEN")));

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
        assertThat(response.payload().path("headSha").asText()).isEqualTo(headSha);
        assertThat(response.payload().path("prNumber").asInt()).isEqualTo(42);
    }

    @Test
    void mergeCheckAndDeployStopBeforeSideEffectsWithoutDeployApproval() {
        ObjectMapper mapper = new ObjectMapper();
        CodingHandlerResultService resultService = mock(CodingHandlerResultService.class);
        CodingToolService toolService = mock(CodingToolService.class);
        CodingRunnerService runner = mock(CodingRunnerService.class);
        DeploymentAdapter deployment = mock(DeploymentAdapter.class);
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

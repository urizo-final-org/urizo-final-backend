package org.urizo.axmodulestudio.backend.cms.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.urizo.axmodulestudio.backend.coding.dto.CodingModelTurnContract;
import org.urizo.axmodulestudio.backend.coding.service.CodingModelTurnService;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ModelUseCase;
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderModelRegistration;
import org.urizo.axmodulestudio.backend.integration.ai.mcp.McpPlatformClient;
import org.urizo.axmodulestudio.backend.orchestration.service.ProfileModelBindingService;

class NaturalCmsStageServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-30T08:00:00Z");
    private static final UUID JOB = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID TRACE = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID PROFILE = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID RESULT = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID PREVIEW = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final String PREVIEW_HASH = "sha256:" + "a".repeat(64);
    private static final NaturalCmsContract.ResourceRef RESOURCE =
            new NaturalCmsContract.ResourceRef("CONTENT", "7");
    private static final Set<String> ALL_TOOLS = Set.of(
            "resolve_cms_target", "validate_cms_command", "create_cms_preview",
            "discard_cms_preview", "revalidate_cms_preview", "apply_cms_preview");

    @Test
    void emptyPolicyIntersectionUsesChatOnlyAndRejectsTheFirstToolExecution() throws Exception {
        Harness harness = new Harness(activeJob());
        when(harness.store.runtimePolicy("Bearer worker", PROFILE)).thenReturn(
                new NaturalCmsStore.RuntimePolicy(Set.of(), "central.default"));
        when(harness.resources.snapshot(RESOURCE)).thenReturn(
                harness.mapper.createObjectNode().put("id", 7));
        when(harness.models.executeNaturalCms(any(), any())).thenReturn(modelResponse(
                "{\"operation\":\"UPDATE\",\"fields\":{"
                        + "\"title\":\"New\",\"body\":\"Body\"}}",
                List.of()));

        assertThatThrownBy(() -> harness.service.execute(
                "Bearer worker", JOB, 1, RESULT,
                stageRequest("cms.preview", RESULT)))
                .isInstanceOfSatisfying(NaturalCmsException.class,
                        failure -> assertThat(failure.code()).isEqualTo("TOOL_NOT_ALLOWED"));

        ArgumentCaptor<CodingModelTurnContract.Request> request =
                ArgumentCaptor.forClass(CodingModelTurnContract.Request.class);
        verify(harness.models).executeNaturalCms(request.capture(), any());
        assertThat(request.getValue().requiredCapabilities()).containsExactly("CHAT");
        assertThat(request.getValue().toolSchemas()).isEmpty();
        verify(harness.profileModelBindings).resolve(
                PROFILE, "preview", "cms.preview", ModelUseCase.CHAT);
        verify(harness.mcp, never()).callTool(any(), any());
    }

    @Test
    void commandPromptUsesTheResourceAndEditableFieldsForAllSupportedTypes()
            throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<PromptCase> cases = List.of(
                new PromptCase(
                        new NaturalCmsContract.ResourceRef("MENU", "1"),
                        (ObjectNode) mapper.readTree("""
                                {"id":1,"name":"About","path":"/about","parentId":null,
                                 "displayOrder":1,"targetType":"CONTENT","targetId":1}
                                """),
                        Set.of("name", "path", "parentId", "displayOrder",
                                "targetType", "targetId")),
                new PromptCase(
                        new NaturalCmsContract.ResourceRef("BOARD", "1"),
                        (ObjectNode) mapper.readTree("""
                                {"id":1,"name":"Notice","description":"Board",
                                 "updatedAt":"2026-08-30T08:00:00Z"}
                                """),
                        Set.of("name", "description")),
                new PromptCase(
                        new NaturalCmsContract.ResourceRef("CONTENT", "1"),
                        (ObjectNode) mapper.readTree("""
                                {"id":1,"title":"About","body":"Body",
                                 "updatedAt":"2026-08-30T08:00:00Z"}
                                """),
                        Set.of("title", "body")),
                new PromptCase(
                        new NaturalCmsContract.ResourceRef("TEMPLATE", "DEFAULT"),
                        (ObjectNode) mapper.readTree("""
                                {"id":"DEFAULT","layout":"default","primaryColor":"#000000",
                                 "siteName":"Site","headerText":null,"footerText":null,
                                 "heroImageUrl":"/hero.jpg","heroTitle":"Hero",
                                 "heroSubtitle":null,"heroButtonLabel":null,
                                 "heroButtonUrl":null,"active":true,
                                 "updatedAt":"2026-08-30T08:00:00Z"}
                                """),
                        Set.of("layout", "primaryColor", "siteName", "headerText",
                                "footerText", "heroImageUrl", "heroTitle", "heroSubtitle",
                                "heroButtonLabel", "heroButtonUrl")));

        for (PromptCase promptCase : cases) {
            Harness harness = new Harness(activeJob(promptCase.resource()));
            when(harness.store.runtimePolicy("Bearer worker", PROFILE)).thenReturn(
                    new NaturalCmsStore.RuntimePolicy(Set.of(), "central.default"));
            when(harness.resources.snapshot(promptCase.resource()))
                    .thenReturn(promptCase.currentState());
            when(harness.models.executeNaturalCms(any(), any())).thenReturn(modelResponse(
                    "{\"operation\":\"UPDATE\",\"fields\":{\"name\":\"New\"}}",
                    List.of()));

            assertThatThrownBy(() -> harness.service.execute(
                    "Bearer worker", JOB, 1, RESULT,
                    stageRequest("cms.preview", RESULT)))
                    .isInstanceOfSatisfying(NaturalCmsException.class,
                            failure -> assertThat(failure.code()).isEqualTo("TOOL_NOT_ALLOWED"));

            ArgumentCaptor<CodingModelTurnContract.Request> request =
                    ArgumentCaptor.forClass(CodingModelTurnContract.Request.class);
            verify(harness.models).executeNaturalCms(request.capture(), any());
            List<JsonNode> messages = request.getValue().messages();
            assertThat(messages.get(0).path("content").asText())
                    .contains("Create one " + promptCase.resource().type() + " UPDATE command")
                    .contains("fields may use only names from editableFields")
                    .doesNotContain("fields title and body");

            JsonNode context = mapper.readTree(messages.get(1).path("content").asText());
            assertThat(context.path("resource").path("type").asText())
                    .isEqualTo(promptCase.resource().type());
            Set<String> editableFields = new HashSet<>();
            context.path("editableFields").forEach(
                    field -> editableFields.add(field.asText()));
            assertThat(editableFields)
                    .containsExactlyInAnyOrderElementsOf(promptCase.editableFields());
        }
    }

    @Test
    void boundNaturalCmsPolicyDecodeFailsClosedForMissingTools() {
        NaturalCmsStore.RuntimePolicy valid = NaturalCmsStore.decodeRuntimePolicy(
                new ObjectMapper(),
                "{\"toolPolicy\":{\"allowedTools\":[\"resolve_cms_target\"]},"
                        + "\"guardrailProfileKey\":\"central.default\"}");
        assertThat(valid.allowedTools()).containsExactly("resolve_cms_target");

        assertThatThrownBy(() -> NaturalCmsStore.decodeRuntimePolicy(
                new ObjectMapper(),
                "{\"toolPolicy\":{},\"guardrailProfileKey\":\"central.default\"}"))
                .isInstanceOfSatisfying(NaturalCmsException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("NATURAL_CMS_STATE_CONFLICT"));
    }

    @Test
    void feedsToolOutputBackToTheModelAndStoresOnlyThePreviewBoundary() throws Exception {
        Harness harness = new Harness(activeJob());
        ObjectNode state = harness.mapper.createObjectNode()
                .put("id", 7).put("title", "Old").put("body", "Old body")
                .put("updatedAt", NOW.toString());
        when(harness.resources.snapshot(RESOURCE)).thenReturn(state);
        when(harness.resources.validateCommand(eq(RESOURCE), any()))
                .thenAnswer(call -> ((JsonNode) call.getArgument(1)).deepCopy());
        when(harness.models.executeNaturalCms(any(), any())).thenReturn(
                modelResponse("", List.of(new CodingModelTurnContract.ToolCall(
                        UUID.fromString("66666666-6666-4666-8666-666666666666"),
                        "resolve_cms_target",
                        harness.mapper.createObjectNode()))),
                modelResponse(
                        "Command follows.\n```json\n"
                                + "{\"operation\":\"UPDATE\",\"fields\":{"
                                + "\"title\":\"New\",\"body\":\"Body\"}}\n```",
                        List.of()));
        when(harness.mcp.callTool(eq("resolve_cms_target"), any())).thenReturn(
                structured(harness.mapper.createObjectNode().put("resolved", true)),
                structured(harness.mapper.createObjectNode().put("resolved", true)));
        when(harness.mcp.callTool(eq("validate_cms_command"), any())).thenReturn(
                structured(harness.mapper.createObjectNode().put("valid", true)));
        ObjectNode preview = harness.mapper.createObjectNode()
                .put("previewId", PREVIEW.toString())
                .put("previewHash", PREVIEW_HASH)
                .set("before", state.deepCopy());
        when(harness.mcp.callTool(eq("create_cms_preview"), any()))
                .thenReturn(structured(preview));

        NaturalCmsContract.StageExecutionResponse response = harness.service.execute(
                "Bearer worker", JOB, 1, RESULT,
                stageRequest("cms.preview", RESULT));

        assertThat(response.resultPort()).isEqualTo("ready");
        assertThat(response.resource()).isEqualTo(RESOURCE);
        assertThat(response.previewId()).isEqualTo(PREVIEW);
        assertThat(response.previewHash()).isEqualTo(PREVIEW_HASH);
        assertThat(response.structuredCommand().has("workspaceId")).isFalse();
        assertThat(response.structuredCommand().has("candidateSha")).isFalse();
        verify(harness.resources, never()).apply(any(), any());

        ArgumentCaptor<CodingModelTurnContract.Request> turns =
                ArgumentCaptor.forClass(CodingModelTurnContract.Request.class);
        verify(harness.models, times(2)).executeNaturalCms(turns.capture(), any());
        assertThat(turns.getAllValues().get(1).messages())
                .anySatisfy(message -> assertThat(message.path("role").asText())
                        .isEqualTo("tool"));
        verify(harness.store).record(eq("Bearer worker"), eq(JOB), eq(1), any());
    }

    @Test
    void repairsOneFencedAnalysisObjectBeforeStrictValidation() throws Exception {
        Harness harness = new Harness(activeJob());
        when(harness.resources.snapshot(RESOURCE)).thenReturn(
                harness.mapper.createObjectNode().put("id", 7));
        when(harness.models.executeNaturalCms(any(), any())).thenReturn(modelResponse(
                "Analysis result:\n```json\n"
                        + "{\"port\":\"feasible\",\"payload\":{\"reason\":\"safe\"}}\n```",
                List.of()));

        NaturalCmsContract.StageExecutionResponse response = harness.service.execute(
                "Bearer worker", JOB, 1, RESULT,
                stageRequest("cms.analyze", RESULT));

        assertThat(response.resultPort()).isEqualTo("feasible");
        assertThat(response.payload().path("reason").asText()).isEqualTo("safe");
    }

    @Test
    void rejectsAnalysisThatRemainsInvalidAfterOneRepair() throws Exception {
        Harness harness = new Harness(activeJob());
        when(harness.resources.snapshot(RESOURCE)).thenReturn(
                harness.mapper.createObjectNode().put("id", 7));
        when(harness.models.executeNaturalCms(any(), any())).thenReturn(modelResponse(
                "prefix {\"port\":\"feasible\",\"payload\":{}} "
                        + "{\"port\":\"feasible\",\"payload\":{}}",
                List.of()));

        assertThatThrownBy(() -> harness.service.execute(
                "Bearer worker", JOB, 1, RESULT,
                stageRequest("cms.analyze", RESULT)))
                .isInstanceOfSatisfying(NaturalCmsException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("CONTRACT_VALIDATION_FAILED"));
    }

    @Test
    void revalidatesWithMcpBeforeApplyingThroughCmsService() throws Exception {
        Harness harness = new Harness(approvedJob());
        ObjectNode command = (ObjectNode) approvedJob().structuredCommand();
        ObjectNode current = harness.mapper.createObjectNode()
                .put("id", 7).put("title", "Old").put("body", "Old body")
                .put("updatedAt", NOW.toString());
        when(harness.resources.validateCommand(RESOURCE, command)).thenReturn(command);
        when(harness.resources.snapshot(RESOURCE)).thenReturn(current);
        when(harness.mcp.callTool(eq("revalidate_cms_preview"), any())).thenReturn(
                structured(harness.mapper.createObjectNode().put("valid", true)));
        ObjectNode ready = harness.mapper.createObjectNode().put("applyReady", true);
        ready.set("command", command.deepCopy());
        when(harness.mcp.callTool(eq("apply_cms_preview"), any()))
                .thenReturn(structured(ready));
        when(harness.resources.apply(RESOURCE, command)).thenReturn(
                harness.mapper.createObjectNode().put("id", 7).put("title", "New"));

        NaturalCmsContract.StageExecutionResponse response = harness.service.execute(
                "Bearer worker", JOB, 1, RESULT,
                stageRequest("cms.apply", RESULT));

        assertThat(response.resultPort()).isEqualTo("applied");
        verify(harness.mcp).callTool(eq("revalidate_cms_preview"), any());
        verify(harness.mcp).callTool(eq("apply_cms_preview"), any());
        verify(harness.resources).apply(RESOURCE, command);
    }

    @Test
    void handlerResultFailureDoesNotLeaveCmsMutationOutsideTheAtomicStoreBoundary()
            throws Exception {
        Harness harness = new Harness(approvedJob());
        ObjectNode command = (ObjectNode) approvedJob().structuredCommand();
        ObjectNode current = harness.mapper.createObjectNode()
                .put("id", 7).put("title", "Old").put("body", "Old body")
                .put("updatedAt", NOW.toString());
        when(harness.resources.validateCommand(RESOURCE, command)).thenReturn(command);
        when(harness.resources.snapshot(RESOURCE)).thenReturn(current);
        when(harness.mcp.callTool(eq("revalidate_cms_preview"), any())).thenReturn(
                structured(harness.mapper.createObjectNode().put("valid", true)));
        ObjectNode ready = harness.mapper.createObjectNode().put("applyReady", true);
        ready.set("command", command.deepCopy());
        when(harness.mcp.callTool(eq("apply_cms_preview"), any()))
                .thenReturn(structured(ready));
        when(harness.resources.apply(RESOURCE, command)).thenReturn(
                harness.mapper.createObjectNode().put("id", 7).put("title", "New"));
        when(harness.store.recordApplied(
                eq("Bearer worker"), eq(JOB), eq(1), eq(RESULT), eq(1), any()))
                .thenThrow(new IllegalStateException("handler result insert failed"));

        assertThatThrownBy(() -> harness.service.execute(
                "Bearer worker", JOB, 1, RESULT,
                stageRequest("cms.apply", RESULT)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("handler result insert failed");

        verify(harness.mcp).callTool(eq("revalidate_cms_preview"), any());
        verify(harness.resources, never()).apply(RESOURCE, command);
    }

    private static NaturalCmsContract.JobResponse activeJob() throws Exception {
        return job("ACTIVE", null, null, null, false);
    }

    private static NaturalCmsContract.JobResponse activeJob(
            NaturalCmsContract.ResourceRef resource) throws Exception {
        return job("ACTIVE", null, null, null, false, resource);
    }

    private static NaturalCmsContract.JobResponse approvedJob() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        return job(
                "WAITING_APPROVAL",
                mapper.readTree("""
                        {"operation":"UPDATE","fields":{"title":"New","body":"Body"}}
                        """),
                PREVIEW,
                PREVIEW_HASH,
                true);
    }

    private static NaturalCmsContract.JobResponse job(
            String status,
            JsonNode command,
            UUID previewId,
            String previewHash,
            boolean previewValid) {
        return job(status, command, previewId, previewHash, previewValid, RESOURCE);
    }

    private static NaturalCmsContract.JobResponse job(
            String status,
            JsonNode command,
            UUID previewId,
            String previewHash,
            boolean previewValid,
            NaturalCmsContract.ResourceRef resource) {
        return new NaturalCmsContract.JobResponse(
                "1.0", JOB, TRACE, PROFILE, 1, 1, status,
                "Update the selected resource", resource, command, previewId, previewHash,
                previewValid,
                "WAITING_APPROVAL".equals(status) ? "APPROVED" : null,
                null,
                NOW,
                NOW);
    }

    private static NaturalCmsContract.StageExecutionRequest stageRequest(
            String handlerKey, UUID resultId) {
        return new NaturalCmsContract.StageExecutionRequest(
                "1.0", TRACE, PROFILE, 1, 1, handlerKey, resultId);
    }

    private static CodingModelTurnContract.Response modelResponse(
            String content, List<CodingModelTurnContract.ToolCall> toolCalls) {
        return new CodingModelTurnContract.Response(
                "1.0",
                UUID.randomUUID(),
                JOB,
                TRACE,
                "natural-cms.test-turn",
                new CodingModelTurnContract.Assistant("assistant", content),
                toolCalls,
                CodingModelTurnContract.TextResponseFormat.text(),
                new CodingModelTurnContract.SelectedModel("OPENAI", "test-model"),
                new CodingModelTurnContract.TokenUsage(1, 1, 2),
                1,
                toolCalls.isEmpty() ? "STOP" : "TOOL_CALLS",
                NOW);
    }

    private static ObjectNode structured(JsonNode content) {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode response = mapper.createObjectNode().put("isError", false);
        response.set("structuredContent", content.deepCopy());
        return response;
    }

    private record PromptCase(
            NaturalCmsContract.ResourceRef resource,
            ObjectNode currentState,
            Set<String> editableFields) {
    }

    private static final class Harness {
        private final ObjectMapper mapper = new ObjectMapper();
        private final NaturalCmsStore store = mock(NaturalCmsStore.class);
        private final NaturalCmsResourceService resources =
                mock(NaturalCmsResourceService.class);
        private final CodingModelTurnService models = mock(CodingModelTurnService.class);
        private final ProfileModelBindingService profileModelBindings =
                mock(ProfileModelBindingService.class);
        private final McpPlatformClient mcp = mock(McpPlatformClient.class);
        private final ObjectProvider<McpPlatformClient> provider = mock(ObjectProvider.class);
        private final NaturalCmsStageService service;

        private Harness(NaturalCmsContract.JobResponse job) {
            when(store.get("Bearer worker", JOB, 1)).thenReturn(job);
            when(store.findResult("Bearer worker", JOB, 1, RESULT))
                    .thenReturn(Optional.empty());
            when(store.runtimePolicy("Bearer worker", PROFILE)).thenReturn(
                    new NaturalCmsStore.RuntimePolicy(ALL_TOOLS, "central.default"));
            when(store.record(eq("Bearer worker"), eq(JOB), eq(1), any()))
                    .thenAnswer(call -> {
                        NaturalCmsContract.StageExecutionResponse value = call.getArgument(3);
                        return new NaturalCmsContract.HandlerResult(
                                value.resultId(), JOB, TRACE, 1, value.handlerKey(),
                                value.resultPort(), value.resource(), value.structuredCommand(),
                                 value.previewId(), value.previewHash(), value.payload(), NOW);
                    });
            when(store.recordApplied(
                    eq("Bearer worker"), eq(JOB), eq(1), eq(RESULT), eq(1), any()))
                    .thenAnswer(call -> {
                        @SuppressWarnings("unchecked")
                        java.util.function.Supplier<NaturalCmsContract.StageExecutionResponse>
                                apply = call.getArgument(5);
                        NaturalCmsContract.StageExecutionResponse value = apply.get();
                        return new NaturalCmsContract.HandlerResult(
                                value.resultId(), JOB, TRACE, 1, value.handlerKey(),
                                value.resultPort(), value.resource(), value.structuredCommand(),
                                value.previewId(), value.previewHash(), value.payload(), NOW);
                    });
            when(provider.getIfAvailable()).thenReturn(mcp);
            when(profileModelBindings.resolve(eq(PROFILE), any(), any(), any()))
                    .thenReturn(List.of(mock(ProviderModelRegistration.class)));
            service = new NaturalCmsStageService(
                    store,
                    resources,
                    models,
                    profileModelBindings,
                    provider,
                    mapper,
                    Clock.fixed(NOW, ZoneOffset.UTC));
        }
    }
}

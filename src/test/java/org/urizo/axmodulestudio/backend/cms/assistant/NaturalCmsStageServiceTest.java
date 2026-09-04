package org.urizo.axmodulestudio.backend.cms.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.urizo.axmodulestudio.backend.orchestration.service.ProfileToolBindingPolicy;

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
    void previewFailsClosedBeforeTheModelWhenRequiredToolsAreMissing() throws Exception {
        Harness harness = new Harness(activeJob());
        when(harness.store.runtimePolicy("Bearer worker", PROFILE)).thenReturn(
                new NaturalCmsStore.RuntimePolicy(Set.of(), "central.default"));
        when(harness.resources.snapshot(RESOURCE)).thenReturn(
                harness.mapper.createObjectNode().put("id", 7));
        assertThatThrownBy(() -> harness.service.execute(
                "Bearer worker", JOB, 1, RESULT,
                stageRequest("cms.preview", RESULT)))
                .isInstanceOfSatisfying(NaturalCmsException.class,
                        failure -> assertThat(failure.code()).isEqualTo("TOOL_NOT_ALLOWED"));

        verify(harness.models, never()).executeNaturalCms(any(), any());
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
            when(harness.resources.snapshot(promptCase.resource()))
                    .thenReturn(promptCase.currentState());
            ObjectNode command = mapper.createObjectNode().put("operation", "UPDATE")
                    .putObject("fields");
            when(harness.models.executeNaturalCms(any(), any())).thenReturn(
                    toolResponse("validate_cms_command", command));
            when(harness.resources.validateCommand(eq(promptCase.resource()), any()))
                    .thenAnswer(call -> ((JsonNode) call.getArgument(1)).deepCopy());
            stubPreviewTools(harness, promptCase.currentState());

            NaturalCmsContract.StageExecutionResponse response = harness.service.execute(
                    "Bearer worker", JOB, 1, RESULT,
                    stageRequest("cms.preview", RESULT));
            assertThat(response.resultPort()).isEqualTo("ready");

            ArgumentCaptor<CodingModelTurnContract.Request> request =
                    ArgumentCaptor.forClass(CodingModelTurnContract.Request.class);
            verify(harness.models).executeNaturalCms(request.capture(), any());
            List<JsonNode> messages = request.getValue().messages();
            // 메뉴만 CREATE·DELETE까지 열려 있어(AI05-006·AI05-007) 첫 문장이 다르다.
            // 나머지 셋은 AI05-013이 정한 UPDATE 문구를 그대로 쓴다.
            String opening = "MENU".equals(promptCase.resource().type())
                    ? "Create one MENU command with operation CREATE, UPDATE or DELETE"
                    : "Create one " + promptCase.resource().type() + " UPDATE command";
            assertThat(messages.get(0).path("content").asText())
                    .contains(opening)
                    .contains("Call validate_cms_command exactly once")
                    .contains("fields may use only names from editableFields")
                    .contains("Send only the fields the request changes")
                    .doesNotContain("Finish with only JSON");

            assertThat(request.getValue().requiredCapabilities())
                    .containsExactly("CHAT", "TOOL_CALLING");
            assertThat(request.getValue().toolSchemas())
                    .singleElement()
                    .satisfies(schema -> {
                        assertThat(schema.path("name").asText())
                                .isEqualTo("validate_cms_command");
                        JsonNode commandSchema = schema.path("inputSchema")
                                .path("properties").path("command");
                        assertThat(commandSchema.path("additionalProperties").asBoolean()).isFalse();
                        assertThat(commandSchema.path("required"))
                                .extracting(JsonNode::asText)
                                .containsExactly("operation", "fields");
                        assertThat(commandSchema.path("properties").path("operation").path("type")
                                .asText()).isEqualTo("string");
                        assertThat(commandSchema.path("properties").path("fields").path("type")
                                .asText()).isEqualTo("object");
                    });

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
    void rejectsTextOnlyOrWrongToolPreviewResponses() throws Exception {
        Harness textOnly = new Harness(activeJob());
        when(textOnly.resources.snapshot(RESOURCE)).thenReturn(
                textOnly.mapper.createObjectNode().put("id", 7));
        when(textOnly.models.executeNaturalCms(any(), any())).thenReturn(modelResponse(
                "{\"operation\":\"UPDATE\",\"fields\":{}}", List.of()));

        assertThatThrownBy(() -> textOnly.service.execute(
                "Bearer worker", JOB, 1, RESULT,
                stageRequest("cms.preview", RESULT)))
                .isInstanceOfSatisfying(NaturalCmsException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("CONTRACT_VALIDATION_FAILED"));
        verify(textOnly.resources, never()).validateCommand(any(), any());

        Harness wrongTool = new Harness(activeJob());
        when(wrongTool.resources.snapshot(RESOURCE)).thenReturn(
                wrongTool.mapper.createObjectNode().put("id", 7));
        when(wrongTool.models.executeNaturalCms(any(), any())).thenReturn(toolResponse(
                "create_cms_preview", wrongTool.mapper.createObjectNode()
                        .put("operation", "UPDATE").putObject("fields")));

        assertThatThrownBy(() -> wrongTool.service.execute(
                "Bearer worker", JOB, 1, RESULT,
                stageRequest("cms.preview", RESULT)))
                .isInstanceOfSatisfying(NaturalCmsException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("CONTRACT_VALIDATION_FAILED"));
        verify(wrongTool.resources, never()).validateCommand(any(), any());
    }

    @Test
    void boundNaturalCmsPolicyDecodeFailsClosedForMissingTools() throws Exception {
        NaturalCmsStore.RuntimePolicy valid = NaturalCmsStore.decodeRuntimePolicy(
                new ObjectMapper(),
                "{\"toolPolicy\":{\"allowedTools\":[\"resolve_cms_target\"]},"
                        + "\"guardrailProfileKey\":\"central.default\"}");
        assertThat(valid.allowedTools()).containsExactly("resolve_cms_target");
        assertThat(valid.toolBindings().legacy()).isTrue();

        NaturalCmsStore.RuntimePolicy bound = NaturalCmsStore.decodeRuntimePolicy(
                new ObjectMapper(), Files.readString(Path.of(
                        "contracts/fixtures/orchestration/natural-cms-handler.snapshot.valid.json")));
        assertThat(bound.toolBindings().modelToolsForNode("preview"))
                .containsExactly("validate_cms_command");
        assertThat(bound.toolBindings().systemToolsForNode("apply"))
                .containsExactlyInAnyOrder("revalidate_cms_preview", "apply_cms_preview");

        assertThatThrownBy(() -> NaturalCmsStore.decodeRuntimePolicy(
                new ObjectMapper(),
                "{\"toolPolicy\":{},\"guardrailProfileKey\":\"central.default\"}"))
                .isInstanceOfSatisfying(NaturalCmsException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("NATURAL_CMS_STATE_CONFLICT"));
    }

    @Test
    void usesOneValidateCommandToolCallAndStoresOnlyThePreviewBoundary() throws Exception {
        Harness harness = new Harness(activeJob());
        ObjectNode state = harness.mapper.createObjectNode()
                .put("id", 7).put("title", "Old").put("body", "Old body")
                .put("updatedAt", NOW.toString());
        when(harness.resources.snapshot(RESOURCE)).thenReturn(state);
        when(harness.resources.validateCommand(eq(RESOURCE), any()))
                .thenAnswer(call -> ((JsonNode) call.getArgument(1)).deepCopy());
        ObjectNode command = harness.mapper.createObjectNode().put("operation", "UPDATE");
        command.putObject("fields").put("title", "New").put("body", "Body");
        when(harness.models.executeNaturalCms(any(), any())).thenReturn(
                toolResponse("validate_cms_command", command));
        when(harness.mcp.callTool(eq("resolve_cms_target"), any())).thenReturn(
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
        verify(harness.models).executeNaturalCms(turns.capture(), any());
        assertThat(turns.getValue().toolSchemas())
                .extracting(schema -> schema.path("name").asText())
                .containsExactly("validate_cms_command");
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

    /**
     * 판정 지시문이 화면 범위를 알려주는지 본다.
     *
     * <p>범위를 주지 않았을 때 메뉴 화면에서 게시글 등록 요청이 feasible로 통과해
     * 명령 단계에서 계약 밖 형식으로 멈췄다. 거부 안내가 화면에 뜨지 않은 원인이다.
     */
    @Test
    void feasibilityPromptNamesWhatTheScreenCanAndCannotChange() throws Exception {
        Harness harness = new Harness(activeJob());
        when(harness.resources.snapshot(RESOURCE)).thenReturn(
                harness.mapper.createObjectNode().put("id", 7));
        when(harness.models.executeNaturalCms(any(), any())).thenReturn(
                modelResponse("{\"port\":\"feasible\",\"payload\":{}}", List.of()));

        harness.service.execute(
                "Bearer worker", JOB, 1, RESULT, stageRequest("cms.analyze", RESULT));

        ArgumentCaptor<CodingModelTurnContract.Request> turn =
                ArgumentCaptor.forClass(CodingModelTurnContract.Request.class);
        verify(harness.models).executeNaturalCms(turn.capture(), any());
        assertThat(system(turn.getValue()))
                .contains("title and body only")
                .contains("Anything else is infeasible")
                .contains("payload.reason");
    }

    /**
     * 명령 지시문이 바뀌는 필드만 보내라고 하는지 본다.
     *
     * <p>전체 필드를 채워 보내던 탓에 연결 요청이 이름까지 바꾸고 이름 변경이 연결을 지웠다.
     */
    @Test
    void menuCommandPromptSeparatesRenameFromLinkAndKeepsDeleteEmpty() throws Exception {
        NaturalCmsContract.ResourceRef menu = new NaturalCmsContract.ResourceRef("MENU", "3");
        Harness harness = new Harness(activeJob(menu));
        ObjectNode state = harness.mapper.createObjectNode()
                .put("id", 3).put("name", "소개").put("path", "/about")
                .put("position", 1).put("targetType", "NONE");
        when(harness.resources.snapshot(menu)).thenReturn(state);
        when(harness.resources.validateCommand(eq(menu), any()))
                .thenAnswer(call -> ((JsonNode) call.getArgument(1)).deepCopy());
        ObjectNode command = harness.mapper.createObjectNode()
                .put("operation", "UPDATE");
        command.putObject("fields").put("position", 3);
        when(harness.models.executeNaturalCms(any(), any())).thenReturn(
                toolResponse("validate_cms_command", command));
        stubPreviewTools(harness, state);

        harness.service.execute(
                "Bearer worker", JOB, 1, RESULT, stageRequest("cms.preview", RESULT));

        ArgumentCaptor<CodingModelTurnContract.Request> turn =
                ArgumentCaptor.forClass(CodingModelTurnContract.Request.class);
        verify(harness.models).executeNaturalCms(turn.capture(), any());
        assertThat(system(turn.getValue()))
                .contains("Renaming sends name alone")
                .contains("Linking sends targetType and targetId alone and never changes the name")
                .contains("DELETE carries no fields")
                .contains("never send displayOrder");
    }

    private static String system(CodingModelTurnContract.Request request) {
        return request.messages().stream()
                .filter(message -> "system".equals(message.path("role").asText()))
                .map(message -> message.path("content").asText())
                .findFirst()
                .orElse("");
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

    private static CodingModelTurnContract.Response toolResponse(String name, JsonNode command) {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode arguments = mapper.createObjectNode();
        arguments.set("command", command.deepCopy());
        return modelResponse("Submitting the command.", List.of(new CodingModelTurnContract.ToolCall(
                UUID.randomUUID(), name, arguments)));
    }

    private static void stubPreviewTools(Harness harness, JsonNode currentState) {
        when(harness.mcp.callTool(eq("resolve_cms_target"), any())).thenReturn(
                structured(harness.mapper.createObjectNode().put("resolved", true)));
        when(harness.mcp.callTool(eq("validate_cms_command"), any())).thenReturn(
                structured(harness.mapper.createObjectNode().put("valid", true)));
        ObjectNode preview = harness.mapper.createObjectNode()
                .put("previewId", PREVIEW.toString())
                .put("previewHash", PREVIEW_HASH);
        preview.set("before", currentState.deepCopy());
        when(harness.mcp.callTool(eq("create_cms_preview"), any()))
                .thenReturn(structured(preview));
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
                    runtimePolicy(mapper));
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

    private static NaturalCmsStore.RuntimePolicy runtimePolicy(ObjectMapper mapper) {
        try {
            JsonNode snapshot = mapper.readTree(Files.readString(Path.of(
                    "contracts/fixtures/orchestration/natural-cms-handler.snapshot.valid.json")));
            ProfileToolBindingPolicy bindings = ProfileToolBindingPolicy.decode(
                    snapshot, ALL_TOOLS);
            return new NaturalCmsStore.RuntimePolicy(
                    ALL_TOOLS, "central.default", bindings);
        }
        catch (java.io.IOException failure) {
            throw new AssertionError(failure);
        }
    }
}

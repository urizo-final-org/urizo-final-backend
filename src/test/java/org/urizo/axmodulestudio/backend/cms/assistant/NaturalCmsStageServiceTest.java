package org.urizo.axmodulestudio.backend.cms.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ProviderToolDefinition;
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
    private static final UUID ACTOR = UUID.fromString("66666666-6666-4666-8666-666666666666");
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
                        Set.of("layout", "primaryColor", "headerText",
                                "footerText", "heroImages", "heroTitle", "heroSubtitle",
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
            when(harness.resources.validateCommand(eq(promptCase.resource()), any(), any()))
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
            // 메뉴(AI05-006·AI05-007), 게시판(AI05-014), 컨텐츠(AI05-015)는 CREATE·DELETE까지
            // 열려 첫 문장이 다르다. 남은 템플릿은 AI05-013이 정한 UPDATE 문구를 그대로 쓴다.
            String type = promptCase.resource().type();
            String opening = "TEMPLATE".equals(type)
                    ? "Create one " + type + " UPDATE command"
                    : "Create one " + type + " command with operation CREATE, UPDATE or DELETE";
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
                        // Exercise the real gateway parser and digest check, not only the mocked model.
                        var definition = ProviderToolDefinition.fromContract(schema);
                        assertThat(definition.schemaDigest()).isEqualTo(
                                NaturalCmsToolContract.MODEL_TOOL_SCHEMA_DIGESTS.get("validate_cms_command"));
                        if ("TEMPLATE".equals(type)) {
                            assertThat(definition.normalizeArguments("""
                                    {"command":{"operation":"UPDATE","fields":{"heroImages":[
                                      {"url":"/api/site/images/5","title":"Photo","description":"Caption"}
                                    ]}}}
                                    """)).contains("heroImages", "/api/site/images/5");
                        }
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
        verify(harness.resources, never()).apply(any(), any(), any());

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
        when(harness.resources.apply(RESOURCE, command, ACTOR)).thenReturn(
                harness.mapper.createObjectNode().put("id", 7).put("title", "New"));

        NaturalCmsContract.StageExecutionResponse response = harness.service.execute(
                "Bearer worker", JOB, 1, RESULT,
                stageRequest("cms.apply", RESULT));

        assertThat(response.resultPort()).isEqualTo("applied");
        verify(harness.mcp).callTool(eq("revalidate_cms_preview"), any());
        verify(harness.mcp).callTool(eq("apply_cms_preview"), any());
        verify(harness.resources).apply(RESOURCE, command, ACTOR);
    }

    @Test
    void templateApplyUsesTheSavedPreviewInsideTheAtomicStoreBoundary() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var resource = new NaturalCmsContract.ResourceRef("TEMPLATE", "CLASSIC");
        var command = mapper.readTree("{\"operation\":\"UPDATE\",\"fields\":{\"heroImages\":[]}}");
        ObjectNode before = mapper.createObjectNode().put("id", "CLASSIC");
        ObjectNode preview = mapper.createObjectNode().put("previewId", PREVIEW.toString()).put("previewHash", PREVIEW_HASH);
        preview.set("resource", mapper.valueToTree(resource)); preview.set("command", command); preview.set("before", before);
        var job = new NaturalCmsContract.JobResponse("1.0", JOB, TRACE, PROFILE, 1, 1, "WAITING_APPROVAL",
                "사진 연결 해제", resource, command, PREVIEW, PREVIEW_HASH, true, "APPROVED", null, NOW, NOW, preview);
        Harness harness = new Harness(job);
        when(harness.resources.validateCommand(resource, command, job.requestText())).thenReturn(command);
        when(harness.resources.snapshot(resource)).thenReturn(before);
        when(harness.mcp.callTool(eq("revalidate_cms_preview"), any())).thenReturn(structured(mapper.createObjectNode().put("valid", true)));
        ObjectNode ready = mapper.createObjectNode().put("applyReady", true); ready.set("command", command);
        when(harness.mcp.callTool(eq("apply_cms_preview"), any())).thenReturn(structured(ready));
        when(harness.resources.applyApprovedTemplate(resource, command, ACTOR, job.requestText(), before)).thenReturn(before);
        assertThat(harness.service.execute("Bearer worker", JOB, 1, RESULT, stageRequest("cms.apply", RESULT)).resultPort()).isEqualTo("applied");
        verify(harness.resources).applyApprovedTemplate(resource, command, ACTOR, job.requestText(), before);
        verify(harness.resources, never()).apply(any(), any(), any());
        preview.put("previewHash", "tampered");
        assertThat(job.preview().path("previewHash").asText()).isEqualTo(PREVIEW_HASH);
        ((ObjectNode) job.preview()).put("previewHash", "tampered again");
        assertThat(job.preview().path("previewHash").asText()).isEqualTo(PREVIEW_HASH);
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
        when(harness.resources.apply(RESOURCE, command, ACTOR)).thenReturn(
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
        verify(harness.resources, never()).apply(RESOURCE, command, ACTOR);
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
                .contains("static content pages only")
                .contains("creating a content page")
                .contains("deleting the selected page")
                // 첨부한 사진을 `올려줘`라고 하면 반려됐다. 사람이 쓰는 말을 범위에 넣어 둔다.
                .contains("already been uploaded")
                .contains("upload, put up or add it")
                // `밑줄 적용해서 작성해줘`도 반려됐다. 본문에 쓸 수 있는 것을 범위에 적어 둔다.
                .contains("bold, italic,")
                .contains("strike, underline, text colour, a marker pen")
                .contains("part of this screen")
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

    /** 게시판은 등록·삭제까지 열렸고 삭제에는 게시물 0건 조건이 붙는다. */
    @Test
    void boardCommandPromptOpensCreateAndDeleteAndNamesTheDeleteLimit() throws Exception {
        NaturalCmsContract.ResourceRef board =
                new NaturalCmsContract.ResourceRef("BOARD", "4");
        ObjectNode state = new ObjectMapper().createObjectNode()
                .put("id", 4).put("name", "공지사항").put("description", "안내");

        assertThat(commandPrompt(board, state))
                .contains("Create one BOARD command with operation CREATE, UPDATE or DELETE")
                .contains("CREATE sends at least name")
                .contains("DELETE carries no fields")
                .contains("A board that still has posts cannot be deleted");
    }

    /** 게시물은 같은 화면의 말단 리소스다. 소속 게시판은 대상 id가 들고 있어 모델이 못 바꾼다. */
    @Test
    void postCommandPromptKeepsThePostInItsBoardAndLimitsMarkdown() throws Exception {
        NaturalCmsContract.ResourceRef post =
                new NaturalCmsContract.ResourceRef("BOARD", "board:4:post:12");
        ObjectNode state = new ObjectMapper().createObjectNode()
                .put("id", "board:4:post:12").put("title", "공지").put("body", "본문");

        assertThat(commandPrompt(post, state))
                .contains("Create one POST command with operation CREATE, UPDATE or DELETE")
                .contains("CREATE sends title and body")
                .contains("never send a board field")
                .contains("ProseMirror document")
                .contains("thumbnailImageId")
                .contains("reference.codes");
    }

    /**
     * 컨텐츠는 등록·삭제까지 열렸고, 본문이 편집기 문서다.
     *
     * <p>모델이 트리를 지어내지 않도록 현재 문서를 고쳐 쓰게 하고 쓸 수 있는 부품을 못박는다.
     * 이미지는 본문에 이미 있는 것이나 화면이 요청에 실어 준 것만 쓴다 — 사람이 올린 사진을
     * 넣는 것은 되고 모델이 어디선가 가져오는 것은 안 된다.
     * 게시판과 달리 삭제에 붙는 조건은 없다.
     */
    @Test
    void contentCommandPromptNamesTheDocumentShapeAndLimitsImageSources() throws Exception {
        NaturalCmsContract.ResourceRef content =
                new NaturalCmsContract.ResourceRef("CONTENT", "7");
        ObjectNode state = new ObjectMapper().createObjectNode()
                .put("id", 7).put("title", "회사 소개")
                .put("body", "{\"type\":\"doc\",\"content\":[]}");

        assertThat(commandPrompt(content, state))
                .contains("Create one CONTENT command with operation CREATE, UPDATE or DELETE")
                .contains("ProseMirror document serialised as a JSON string")
                .contains("change only the parts the request asks for")
                .contains("A picture is an image node, never a link")
                .contains("already appears in currentState.body")
                .contains("lists as an attached image")
                .contains("Never invent a src")
                // `AI05-017` 2차에서 색과 형광펜이 들어와 계약이 넓어졌다.
                .contains("bold, italic, strike, underline, textStyle, highlight or link marks")
                .contains("blockquote and horizontalRule")
                .contains("Only these colours exist")
                .contains("Never invent another colour")
                .contains("never use the mark name as the key")
                .contains("CREATE sends title and body")
                .contains("DELETE carries no fields")
                .doesNotContain("cannot be deleted");
    }

    /**
     * 게시판 화면에서는 글쓰기가 범위 안이다.
     *
     * <p>공통 문구가 게시물 작성을 범위 밖으로 못박고 있어 그대로 쓰면 전부 거부된다.
     */
    @Test
    void feasibilityPromptOpensPostWritingOnlyOnTheBoardScreen() throws Exception {
        NaturalCmsContract.ResourceRef post =
                new NaturalCmsContract.ResourceRef("BOARD", "board:4:post:12");
        Harness harness = new Harness(activeJob(post));
        when(harness.resources.snapshot(post)).thenReturn(
                harness.mapper.createObjectNode().put("id", "board:4:post:12"));
        when(harness.models.executeNaturalCms(any(), any())).thenReturn(
                modelResponse("{\"port\":\"feasible\",\"payload\":{}}", List.of()));

        harness.service.execute(
                "Bearer worker", JOB, 1, RESULT, stageRequest("cms.analyze", RESULT));

        ArgumentCaptor<CodingModelTurnContract.Request> turn =
                ArgumentCaptor.forClass(CodingModelTurnContract.Request.class);
        verify(harness.models).executeNaturalCms(turn.capture(), any());
        assertThat(system(turn.getValue()))
                .contains("writing a new post")
                .contains("changing the board itself")
                .doesNotContain("including writing posts");
    }

    /**
     * 가드레일이 닫은 동작은 판정 단계에서 걸러야 관리자가 이유를 본다.
     *
     * <p>명령 단계 검증도 막지만 그쪽은 예외를 던질 뿐이라 Job이 {@code ACTIVE}로 남고 화면은
     * 「미리보기를 받지 못했습니다」로 끝난다. 판정 단계는 {@code infeasible} 포트가 있어
     * 사유를 남기고 정상 반려된다.
     */
    @Test
    void feasibilityPromptSaysWhichOperationsTheAdministratorSwitchedOff() throws Exception {
        NaturalCmsContract.ResourceRef menu = new NaturalCmsContract.ResourceRef("MENU", "3");
        Harness harness = new Harness(activeJob(menu));
        when(harness.resources.snapshot(menu)).thenReturn(
                harness.mapper.createObjectNode().put("id", 3));
        when(harness.resources.openedOperations(menu))
                .thenReturn(Set.of("CREATE", "UPDATE", "DELETE"));
        when(harness.resources.operations(menu)).thenReturn(Set.of("CREATE", "UPDATE"));
        when(harness.models.executeNaturalCms(any(), any())).thenReturn(
                modelResponse("{\"port\":\"feasible\",\"payload\":{}}", List.of()));

        harness.service.execute(
                "Bearer worker", JOB, 1, RESULT, stageRequest("cms.analyze", RESULT));

        ArgumentCaptor<CodingModelTurnContract.Request> turn =
                ArgumentCaptor.forClass(CodingModelTurnContract.Request.class);
        verify(harness.models).executeNaturalCms(turn.capture(), any());
        assertThat(system(turn.getValue()))
                .contains("switched off deleting")
                .contains("guardrail setting turned it off")
                // 켜져 있는 동작까지 껐다고 말하면 되는 요청이 반려된다.
                .doesNotContain("switched off creating");
    }

    /** 코드가 열지 않은 동작은 「관리자가 껐다」가 아니다. 켤 수 있는 것처럼 들린다. */
    @Test
    void feasibilityPromptStaysSilentWhenTheAdministratorClosedNothing() throws Exception {
        NaturalCmsContract.ResourceRef menu = new NaturalCmsContract.ResourceRef("MENU", "3");
        Harness harness = new Harness(activeJob(menu));
        when(harness.resources.snapshot(menu)).thenReturn(
                harness.mapper.createObjectNode().put("id", 3));
        when(harness.resources.openedOperations(menu)).thenReturn(Set.of("UPDATE"));
        when(harness.resources.operations(menu)).thenReturn(Set.of("UPDATE"));
        when(harness.models.executeNaturalCms(any(), any())).thenReturn(
                modelResponse("{\"port\":\"feasible\",\"payload\":{}}", List.of()));

        harness.service.execute(
                "Bearer worker", JOB, 1, RESULT, stageRequest("cms.analyze", RESULT));

        ArgumentCaptor<CodingModelTurnContract.Request> turn =
                ArgumentCaptor.forClass(CodingModelTurnContract.Request.class);
        verify(harness.models).executeNaturalCms(turn.capture(), any());
        assertThat(system(turn.getValue())).doesNotContain("switched off");
    }

    /** 모든 동작이 꺼졌으면 물어볼 것이 없다. 모델을 부르면 토큰만 쓰고 같은 답이 온다. */
    @Test
    void refusesWithoutCallingTheModelWhenEveryOperationIsClosed() throws Exception {
        NaturalCmsContract.ResourceRef menu = new NaturalCmsContract.ResourceRef("MENU", "3");
        Harness harness = new Harness(activeJob(menu));
        when(harness.resources.operations(menu)).thenReturn(Set.of());

        NaturalCmsContract.StageExecutionResponse response = harness.service.execute(
                "Bearer worker", JOB, 1, RESULT, stageRequest("cms.analyze", RESULT));

        assertThat(response.resultPort()).isEqualTo("infeasible");
        // 화면이 「요청을 고치세요」와 「관리자에게 문의하세요」를 가려 말하려면 분류가 필요하다.
        assertThat(response.payload().path("refusalCode").asText())
                .isEqualTo(NaturalCmsRefusal.OPERATION_NOT_ALLOWED.code());
        assertThat(response.payload().path("reason").asText()).contains("가드레일 설정");
        // 화면이 「등록·수정·삭제가 꺼져 있습니다」라고 이름을 대려면 키가 필요하다.
        assertThat(response.payload().path("closedOperations").toString())
                .isEqualTo("[\"CREATE\",\"DELETE\",\"UPDATE\"]");
        verifyNoInteractions(harness.models);
    }

    /**
     * 판정을 뒤집는 것은 서버다.
     *
     * <p>지시문만으로는 모자랐다. 삭제를 끄면 모델이 따랐지만 수정을 끄니 같은 화면에서 그대로
     * {@code feasible}을 냈다. 바로 위에 「이 화면은 제목과 본문을 바꾼다」가 적혀 있어 문장끼리
     * 부딪힌다. 그대로 두면 명령 단계에서 예외가 나고 Job이 {@code ACTIVE}로 남아 화면은
     * 「미리보기를 받지 못했습니다」로 끝난다.
     */
    @Test
    void refusesWhenTheModelCallsAClosedOperationFeasible() throws Exception {
        NaturalCmsContract.ResourceRef menu = new NaturalCmsContract.ResourceRef("MENU", "3");
        Harness harness = new Harness(activeJob(menu));
        when(harness.resources.snapshot(menu)).thenReturn(
                harness.mapper.createObjectNode().put("id", 3));
        when(harness.resources.openedOperations(menu))
                .thenReturn(Set.of("CREATE", "UPDATE", "DELETE"));
        when(harness.resources.operations(menu)).thenReturn(Set.of("CREATE", "DELETE"));
        when(harness.models.executeNaturalCms(any(), any())).thenReturn(modelResponse(
                "{\"port\":\"feasible\",\"payload\":{\"operation\":\"UPDATE\"}}", List.of()));

        NaturalCmsContract.StageExecutionResponse response = harness.service.execute(
                "Bearer worker", JOB, 1, RESULT, stageRequest("cms.analyze", RESULT));

        assertThat(response.resultPort()).isEqualTo("infeasible");
        assertThat(response.payload().path("refusalCode").asText())
                .isEqualTo(NaturalCmsRefusal.OPERATION_NOT_ALLOWED.code());
        // 요청이 막힌 동작만 싣는다. 등록·삭제는 켜져 있으므로 화면이 그것까지 말하면 안 된다.
        assertThat(response.payload().path("closedOperations").toString())
                .isEqualTo("[\"UPDATE\"]");
        assertThat(response.payload().path("reason").asText()).contains("수정");
    }

    /** 켜져 있는 동작은 그대로 통과한다. 되는 요청을 막으면 가드레일이 아니라 고장이다. */
    @Test
    void letsAnOpenOperationThroughEvenWhenAnotherOneIsClosed() throws Exception {
        NaturalCmsContract.ResourceRef menu = new NaturalCmsContract.ResourceRef("MENU", "3");
        Harness harness = new Harness(activeJob(menu));
        when(harness.resources.snapshot(menu)).thenReturn(
                harness.mapper.createObjectNode().put("id", 3));
        when(harness.resources.openedOperations(menu))
                .thenReturn(Set.of("CREATE", "UPDATE", "DELETE"));
        when(harness.resources.operations(menu)).thenReturn(Set.of("CREATE", "DELETE"));
        when(harness.models.executeNaturalCms(any(), any())).thenReturn(modelResponse(
                "{\"port\":\"feasible\",\"payload\":{\"operation\":\"DELETE\"}}", List.of()));

        NaturalCmsContract.StageExecutionResponse response = harness.service.execute(
                "Bearer worker", JOB, 1, RESULT, stageRequest("cms.analyze", RESULT));

        assertThat(response.resultPort()).isEqualTo("feasible");
    }

    /**
     * 동작 이름은 아무것도 꺼져 있지 않아도 요구한다.
     *
     * <p>끈 것이 있을 때만 물어보면, 관리자가 무언가를 끄는 순간 모델이 처음 보는 필드를 받는다.
     * 그때 헷갈리면 가드레일이 가장 필요한 순간에 판정이 흔들린다.
     */
    @Test
    void feasibilityPromptAlwaysAsksWhichOperationTheRequestNeeds() throws Exception {
        NaturalCmsContract.ResourceRef menu = new NaturalCmsContract.ResourceRef("MENU", "3");
        Harness harness = new Harness(activeJob(menu));
        when(harness.resources.snapshot(menu)).thenReturn(
                harness.mapper.createObjectNode().put("id", 3));
        when(harness.models.executeNaturalCms(any(), any())).thenReturn(
                modelResponse("{\"port\":\"feasible\",\"payload\":{}}", List.of()));

        harness.service.execute(
                "Bearer worker", JOB, 1, RESULT, stageRequest("cms.analyze", RESULT));

        ArgumentCaptor<CodingModelTurnContract.Request> turn =
                ArgumentCaptor.forClass(CodingModelTurnContract.Request.class);
        verify(harness.models).executeNaturalCms(turn.capture(), any());
        assertThat(system(turn.getValue()))
                .contains("payload.operation exactly one of CREATE, UPDATE or DELETE")
                .doesNotContain("switched off");
    }

    /**
     * 메뉴 대상도 나머지 세 대상을 제외한다.
     *
     * <p>메뉴만 공통 제외 문구를 그대로 써서 게시판과 컨텐츠가 빠져 있었다. 메뉴 화면에서
     * 게시판을 만들어 달라는 요청이 판정에서 걸러지지 않고 명령 단계까지 내려갔다.
     *
     * <p>제외에 `themselves`가 붙어 있는지도 본다. 메뉴 범위에 `어느 컨텐츠나 게시판에
     * 연결하는지`가 있어서, 목적어 없이 제외하면 연결 변경까지 범위 밖으로 읽힌다.
     */
    @Test
    void feasibilityPromptKeepsOtherResourcesOutsideTheMenuTarget() throws Exception {
        NaturalCmsContract.ResourceRef menu = new NaturalCmsContract.ResourceRef("MENU", "3");
        Harness harness = new Harness(activeJob(menu));
        when(harness.resources.snapshot(menu)).thenReturn(
                harness.mapper.createObjectNode().put("id", 3));
        when(harness.models.executeNaturalCms(any(), any())).thenReturn(
                modelResponse("{\"port\":\"feasible\",\"payload\":{}}", List.of()));

        harness.service.execute(
                "Bearer worker", JOB, 1, RESULT, stageRequest("cms.analyze", RESULT));

        ArgumentCaptor<CodingModelTurnContract.Request> turn =
                ArgumentCaptor.forClass(CodingModelTurnContract.Request.class);
        verify(harness.models).executeNaturalCms(turn.capture(), any());
        assertThat(system(turn.getValue()))
                .contains("menus only")
                .contains("writing or editing posts")
                .contains("creating or changing boards and static content pages themselves")
                .contains("templates and members")
                // 연결 대상 변경은 메뉴 범위 안이다. 제외 문구가 그것까지 덮으면 안 된다.
                .contains("content or board it links to");
    }

    /**
     * 게시판 대상은 게시물 작성이 범위 밖이고, 삭제 조건의 근거를 함께 준다.
     *
     * <p>조건만 알리고 근거를 주지 않으면 모델이 확인할 수단이 없어 같은 요청이 문장에 따라
     * 갈렸다. `이 게시판 지워줘`는 통과하고 `지워줘`는 거부되던 흔들림이다.
     */
    @Test
    void feasibilityPromptKeepsPostWritingOutsideTheBoardTarget() throws Exception {
        NaturalCmsContract.ResourceRef board =
                new NaturalCmsContract.ResourceRef("BOARD", "4");
        Harness harness = new Harness(activeJob(board));
        when(harness.resources.snapshot(board)).thenReturn(
                harness.mapper.createObjectNode().put("id", 4));
        when(harness.models.executeNaturalCms(any(), any())).thenReturn(
                modelResponse("{\"port\":\"feasible\",\"payload\":{}}", List.of()));

        harness.service.execute(
                "Bearer worker", JOB, 1, RESULT, stageRequest("cms.analyze", RESULT));

        ArgumentCaptor<CodingModelTurnContract.Request> turn =
                ArgumentCaptor.forClass(CodingModelTurnContract.Request.class);
        verify(harness.models).executeNaturalCms(turn.capture(), any());
        assertThat(system(turn.getValue()))
                .contains("boards only")
                .contains("deleting a board")
                .contains("reference.posts")
                // 조건이 삭제 밖으로 번지면 게시물 있는 게시판은 이름 변경까지 거부된다.
                .contains("restricts deletion only")
                .contains("stay feasible whatever reference.posts is")
                .contains("writing or editing posts");
    }

    /** 명령 단계의 system 지시문 하나만 꺼내 본다. */
    private static String commandPrompt(
            NaturalCmsContract.ResourceRef resource, ObjectNode state) throws Exception {
        Harness harness = new Harness(activeJob(resource));
        when(harness.resources.snapshot(resource)).thenReturn(state);
        when(harness.resources.validateCommand(eq(resource), any()))
                .thenAnswer(call -> ((JsonNode) call.getArgument(1)).deepCopy());
        ObjectNode command = harness.mapper.createObjectNode().put("operation", "UPDATE");
        command.putObject("fields").put("title", "새 제목");
        when(harness.models.executeNaturalCms(any(), any())).thenReturn(
                toolResponse("validate_cms_command", command));
        stubPreviewTools(harness, state);

        harness.service.execute(
                "Bearer worker", JOB, 1, RESULT, stageRequest("cms.preview", RESULT));

        ArgumentCaptor<CodingModelTurnContract.Request> turn =
                ArgumentCaptor.forClass(CodingModelTurnContract.Request.class);
        verify(harness.models).executeNaturalCms(turn.capture(), any());
        return system(turn.getValue());
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
            // Mockito 기본값은 빈 Set이라 「모든 동작이 꺼짐」으로 읽힌다. 그러면 판정 단계가
            // 모델을 부르지 않고 바로 반려해, 지시문을 보는 테스트가 전부 헛돈다.
            when(resources.openedOperations(any()))
                    .thenReturn(Set.of("CREATE", "UPDATE", "DELETE"));
            when(resources.operations(any()))
                    .thenReturn(Set.of("CREATE", "UPDATE", "DELETE"));
            when(store.get("Bearer worker", JOB, 1)).thenReturn(job);
            when(store.actorId("Bearer worker", JOB)).thenReturn(ACTOR);
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

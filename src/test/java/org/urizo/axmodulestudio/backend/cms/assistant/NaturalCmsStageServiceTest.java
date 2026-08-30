package org.urizo.axmodulestudio.backend.cms.assistant;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.urizo.axmodulestudio.backend.coding.dto.CodingModelTurnContract;
import org.urizo.axmodulestudio.backend.coding.service.CodingModelTurnService;
import org.urizo.axmodulestudio.backend.integration.ai.mcp.McpPlatformClient;

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

    @Test
    void feedsToolOutputBackToTheModelAndStoresOnlyThePreviewBoundary() throws Exception {
        Harness harness = new Harness(activeJob());
        ObjectNode state = harness.mapper.createObjectNode()
                .put("id", 7).put("title", "Old").put("body", "Old body")
                .put("updatedAt", NOW.toString());
        when(harness.resources.snapshot(RESOURCE)).thenReturn(state);
        when(harness.resources.validateCommand(eq(RESOURCE), any()))
                .thenAnswer(call -> ((JsonNode) call.getArgument(1)).deepCopy());
        when(harness.models.executeNaturalCms(any())).thenReturn(
                modelResponse("", List.of(new CodingModelTurnContract.ToolCall(
                        UUID.fromString("66666666-6666-4666-8666-666666666666"),
                        "resolve_cms_target",
                        harness.mapper.createObjectNode()))),
                modelResponse(
                        "{\"operation\":\"UPDATE\",\"fields\":{"
                                + "\"title\":\"New\",\"body\":\"Body\"}}",
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
        verify(harness.models, times(2)).executeNaturalCms(turns.capture());
        assertThat(turns.getAllValues().get(1).messages())
                .anySatisfy(message -> assertThat(message.path("role").asText())
                        .isEqualTo("tool"));
        verify(harness.store).record(eq("Bearer worker"), eq(JOB), eq(1), any());
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

    private static NaturalCmsContract.JobResponse activeJob() throws Exception {
        return job("ACTIVE", null, null, null, false);
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
        return new NaturalCmsContract.JobResponse(
                "1.0", JOB, TRACE, PROFILE, 1, 1, status,
                "Update the content", RESOURCE, command, previewId, previewHash,
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

    private static final class Harness {
        private final ObjectMapper mapper = new ObjectMapper();
        private final NaturalCmsStore store = mock(NaturalCmsStore.class);
        private final NaturalCmsResourceService resources =
                mock(NaturalCmsResourceService.class);
        private final CodingModelTurnService models = mock(CodingModelTurnService.class);
        private final McpPlatformClient mcp = mock(McpPlatformClient.class);
        private final ObjectProvider<McpPlatformClient> provider = mock(ObjectProvider.class);
        private final NaturalCmsStageService service;

        private Harness(NaturalCmsContract.JobResponse job) {
            when(store.get("Bearer worker", JOB, 1)).thenReturn(job);
            when(store.findResult("Bearer worker", JOB, 1, RESULT))
                    .thenReturn(Optional.empty());
            when(store.record(eq("Bearer worker"), eq(JOB), eq(1), any()))
                    .thenAnswer(call -> {
                        NaturalCmsContract.StageExecutionResponse value = call.getArgument(3);
                        return new NaturalCmsContract.HandlerResult(
                                value.resultId(), JOB, TRACE, 1, value.handlerKey(),
                                value.resultPort(), value.resource(), value.structuredCommand(),
                                value.previewId(), value.previewHash(), value.payload(), NOW);
                    });
            when(provider.getIfAvailable()).thenReturn(mcp);
            service = new NaturalCmsStageService(
                    store,
                    resources,
                    models,
                    provider,
                    mapper,
                    Clock.fixed(NOW, ZoneOffset.UTC));
        }
    }
}

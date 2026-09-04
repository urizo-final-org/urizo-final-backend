package org.urizo.axmodulestudio.backend.coding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.urizo.axmodulestudio.backend.auth.entity.AdminRole;
import org.urizo.axmodulestudio.backend.auth.security.AuthenticatedActor;
import org.urizo.axmodulestudio.backend.coding.dto.CodingConsoleContract;
import org.urizo.axmodulestudio.backend.coding.dto.CodingHandlerContract;
import org.urizo.axmodulestudio.backend.coding.dto.CodingModelTurnContract;
import org.urizo.axmodulestudio.backend.coding.dto.CodingJobLifecycleContract;
import org.urizo.axmodulestudio.backend.orchestration.repository.ProfileVersionRepository;

/**
 * The thirteen fields a screen cannot supply, and the one of them that must be real.
 */
class CodingJobIntakeServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-02T00:00:00Z");
    private static final UUID TRACE = UUID.fromString("66666666-6666-4666-8666-666666666666");
    private static final UUID ACTOR = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID PROFILE = UUID.fromString("e67efb12-c6cc-47f1-a12a-87c493d16762");
    private static final String DEV_SHA = "0e0b6c478f361f3db3891f549e70c9458db0cada";
    private static final UUID JOB = UUID.fromString("55555555-5555-4555-8555-555555555555");

    private final ObjectMapper mapper = new ObjectMapper();
    private final CodingHandlerCommandService commands = mock(CodingHandlerCommandService.class);
    private final CodingRunnerService runner = mock(CodingRunnerService.class);
    private final ProfileVersionRepository profiles = mock(ProfileVersionRepository.class);
    private final GuardrailPathSelectionService guardrail =
            mock(GuardrailPathSelectionService.class);
    private final CodingModelTurnService turns = mock(CodingModelTurnService.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<CodingModelTurnService> turnProvider =
            mock(ObjectProvider.class);

    private CodingJobIntakeService service() {
        return new CodingJobIntakeService(
                commands, runner, profiles, guardrail, turnProvider, mapper,
                Clock.fixed(NOW, ZoneOffset.UTC),
                // A real wait would make the suite sleep; the polling itself is not what is
                // under test, only what happens at each outcome.
                3, Duration.ofMillis(1));
    }

    private static AuthenticatedActor actor() {
        return new AuthenticatedActor(ACTOR, "관리자", AdminRole.GENERAL_ADMIN);
    }

    private void activeProfile() {
        ObjectNode snapshot = new ObjectMapper().createObjectNode();
        for (String id : List.of("start", "guardrail", "analyze", "code", "end")) {
            snapshot.withArray("nodes").addObject().put("id", id);
        }
        when(profiles.findAll("LLM_OPS")).thenReturn(List.of(
                new ProfileVersionRepository.AdminStoredProfileVersion(
                        PROFILE, "LLM_OPS", 6, "ACTIVE", NOW, snapshot)));
    }

    @Test
    void aCreatedJobQueuesTheWorkspaceItsCodeStageWillNeed() {
        activeProfile();
        runnerAnswers(DEV_SHA);
        when(commands.create(any(), any(), any(), any(), any())).thenReturn(created());

        service().create(actor(), TRACE, "key-1", request("backend"));

        // The code stage's MCP tools resolve their workspace by the Job's own id, and the
        // runner wants the bare commit. The 8/31 walkthrough queued this task by hand; the
        // product flow forgot it, so every Job died at its first tool call.
        ArgumentCaptor<JsonNode> payload = ArgumentCaptor.forClass(JsonNode.class);
        verify(runner).enqueue(eq("CREATE_WORKTREE"), payload.capture());
        assertThat(payload.getValue().path("repo").asText()).isEqualTo("backend");
        assertThat(payload.getValue().path("baseSha").asText()).isEqualTo(DEV_SHA);
        assertThat(payload.getValue().path("workspaceId").asText()).isEqualTo(JOB.toString());
    }

    private void runnerAnswers(String sha) {
        UUID taskId = UUID.fromString("99999999-9999-4999-8999-999999999999");
        when(runner.enqueue(eq("PREPARE_SCAN_WORKTREE"), any(JsonNode.class))).thenReturn(taskId);
        ObjectNode result = new ObjectMapper().createObjectNode()
                .put("repo", "backend").put("sha", sha);
        when(runner.taskOutcome(taskId, "PREPARE_SCAN_WORKTREE"))
                .thenReturn(new CodingRunnerService.TaskOutcome("SUCCEEDED", null, result));
    }

    /** The intake now queues the code stage's workspace off this response's job id. */
    private static CodingHandlerContract.CreateCodingJobResponse created() {
        CodingJobLifecycleContract.JobResponse job = new CodingJobLifecycleContract.JobResponse(
                "1.0", JOB, TRACE, PROFILE, ACTOR, ACTOR, ACTOR,
                "start", CodingJobLifecycleContract.Status.PENDING, 1,
                "coding-plan-v1", List.of("CHAT"), List.of("start"),
                NOW.plus(Duration.ofHours(1)), NOW, null, NOW, null, null);
        return new CodingHandlerContract.CreateCodingJobResponse("1.0", job, null);
    }

    private static CodingConsoleContract.CreateJobRequest request(String repository) {
        return new CodingConsoleContract.CreateJobRequest(
                repository, "회원 목록에 가입일도 보이게 해줘");
    }

    @Test
    void fillsTheContractFromTheActiveProfileAndTheRunnersRealHead() {
        activeProfile();
        runnerAnswers(DEV_SHA);
        when(commands.create(any(), any(), any(), any(), any())).thenReturn(created());

        service().create(actor(), TRACE, "key-1", request("backend"));

        ArgumentCaptor<CodingHandlerContract.CreateCodingJobRequest> sent =
                ArgumentCaptor.forClass(CodingHandlerContract.CreateCodingJobRequest.class);
        verify(commands).create(any(), eq(TRACE), eq("key-1"), sent.capture(), any());
        CodingHandlerContract.CreateCodingJobRequest built = sent.getValue();

        // The runner reports a bare sha; the Job contract's pattern demands the prefix.
        assertThat(built.baseSha()).isEqualTo("sha1:" + DEV_SHA);
        assertThat(built.profileVersionId()).isEqualTo(PROFILE);
        assertThat(built.graphStep()).isEqualTo("start");
        // Omitting STRUCTURED_OUTPUT is accepted here and refused at the first model turn,
        // which then reads as an unrelated failure much later.
        assertThat(built.allowedCapabilities())
                .containsExactly("CHAT", "TOOL_CALLING", "STRUCTURED_OUTPUT");
        // Taken from the snapshot rather than hardcoded: the active profile is already at v6
        // and the recipe written down for v5 is stale.
        assertThat(built.allowedNodes())
                .containsExactly("start", "guardrail", "analyze", "code", "end");
        assertThat(built.requestText()).isEqualTo("회원 목록에 가입일도 보이게 해줘");
        assertThat(built.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(1)));
    }

    @Test
    void refusesTheWordTheRunnerSendsWhenItWillNotTouchTheScanFolder() {
        activeProfile();
        // The runner answers 'unchanged' rather than a commit when someone has edited the scan
        // folder. Accepting it would create a Job that fails at git worktree add much later.
        runnerAnswers("unchanged");

        assertThatThrownBy(() -> service().create(actor(), TRACE, "key-1", request("backend")))
                .isInstanceOf(CodingJobLifecycleException.class)
                .hasMessageContaining("현재 코드 기준");
        verify(commands, never()).create(any(), any(), any(), any(), any());
    }

    @Test
    void saysSoWhenNobodyStartedTheRunner() {
        activeProfile();
        UUID taskId = UUID.fromString("99999999-9999-4999-8999-999999999999");
        when(runner.enqueue(eq("PREPARE_SCAN_WORKTREE"), any(JsonNode.class))).thenReturn(taskId);
        // The runner lives outside Docker and a person has to start it. Nothing claims the task.
        when(runner.taskOutcome(taskId, "PREPARE_SCAN_WORKTREE"))
                .thenReturn(new CodingRunnerService.TaskOutcome("PENDING", null, null));

        assertThatThrownBy(() -> service().create(actor(), TRACE, "key-1", request("backend")))
                .isInstanceOf(CodingJobLifecycleException.class)
                .hasMessageContaining("실행기가 응답하지 않습니다");
        verify(commands, never()).create(any(), any(), any(), any(), any());
    }

    @Test
    void acceptsAFrontendRequestAndRecordsItAsAFrontendJob() {
        // The frontend was refused here while the runner had no way to test it. It has one now,
        // and the Job has to carry which repository it is in: the stage that queues the build
        // reads that back rather than assuming the only repository there used to be.
        activeProfile();
        runnerAnswers(DEV_SHA);
        when(commands.create(any(), any(), any(), any(), any())).thenReturn(created());

        service().create(actor(), TRACE, "key-1", request("frontend"));

        ArgumentCaptor<CodingHandlerContract.CreateCodingJobRequest> sent =
                ArgumentCaptor.forClass(CodingHandlerContract.CreateCodingJobRequest.class);
        verify(commands).create(any(), any(), any(), sent.capture(), any());
        assertThat(sent.getValue().repositoryId())
                .isEqualTo(CodingRepositories.identifierOf("frontend"));

        ArgumentCaptor<JsonNode> payload = ArgumentCaptor.forClass(JsonNode.class);
        verify(runner).enqueue(eq("CREATE_WORKTREE"), payload.capture());
        assertThat(payload.getValue().path("repo").asText()).isEqualTo("frontend");
    }

    /**
     * The analyst was seen to wave one of these through. With no allowed folder in this
     * repository the file list it is handed is empty, and the label list alone did not stop it -
     * so the whole pipeline ran to reach a verdict that was already decided at the request.
     */
    /** A structured classifier answer, as the turn service would hand it back. */
    private void classifierAnswers(String target, String firstText, String secondText) {
        when(turnProvider.getIfAvailable()).thenReturn(turns);
        ObjectNode verdict = mapper.createObjectNode()
                .put("target", target)
                .put("firstText", firstText)
                .put("secondText", secondText);
        when(turns.executeNaturalCms(any())).thenReturn(new CodingModelTurnContract.Response(
                "1.0", JOB, JOB, TRACE, "coding-intake.test-key",
                new CodingModelTurnContract.Assistant("assistant", ""),
                List.of(),
                new CodingModelTurnContract.JsonSchemaResponseFormat(
                        "JSON_SCHEMA", "sha256:" + "a".repeat(64), verdict),
                new CodingModelTurnContract.SelectedModel("OPENAI", "test-model"),
                new CodingModelTurnContract.TokenUsage(1, 1, 2),
                10L, "STOP", NOW));
    }

    /**
     * The screen stopped asking which side the sentence is about, because the writer cannot
     * know. An empty repository is the server's cue to read the sentence itself.
     */
    @Test
    void classifiesABlankRepositoryAndRunsTheJobOnTheAnsweredSide() {
        activeProfile();
        runnerAnswers(DEV_SHA);
        classifierAnswers("screen", "", "");
        when(commands.create(any(), any(), any(), any(), any())).thenReturn(created());

        CodingConsoleContract.CreateJobOutcome outcome =
                service().create(actor(), TRACE, "key-1", request(null));

        assertThat(outcome.split()).isNull();
        ArgumentCaptor<CodingHandlerContract.CreateCodingJobRequest> sent =
                ArgumentCaptor.forClass(CodingHandlerContract.CreateCodingJobRequest.class);
        verify(commands).create(any(), any(), any(), sent.capture(), any());
        assertThat(sent.getValue().repositoryId())
                .isEqualTo(CodingRepositories.identifierOf("frontend"));
    }

    /**
     * A both-sides sentence starts its data half at once rather than asking the requester to
     * confirm a split they cannot judge. The split rides back so the screen can say - in the
     * classifier's phrasing of the requester's own words - what runs now and what follows.
     */
    @Test
    void aBothSidesSentenceStartsTheDataHalfAndReturnsBothParts() {
        activeProfile();
        runnerAnswers(DEV_SHA);
        classifierAnswers("both", "회원 목록에 가입일 정보가 담기게 해줘", "목록 화면에 가입일 칸을 보이게 해줘");
        when(commands.create(any(), any(), any(), any(), any())).thenReturn(created());

        CodingConsoleContract.CreateJobOutcome outcome =
                service().create(actor(), TRACE, "key-1", request(null));

        assertThat(outcome.split()).isNotNull();
        assertThat(outcome.split().firstText()).contains("담기게");
        assertThat(outcome.split().secondText()).contains("보이게");
        ArgumentCaptor<CodingHandlerContract.CreateCodingJobRequest> sent =
                ArgumentCaptor.forClass(CodingHandlerContract.CreateCodingJobRequest.class);
        verify(commands).create(any(), any(), any(), sent.capture(), any());
        assertThat(sent.getValue().repositoryId())
                .isEqualTo(CodingRepositories.identifierOf("backend"));
        assertThat(sent.getValue().requestText()).isEqualTo("회원 목록에 가입일 정보가 담기게 해줘");
    }

    /** Guessing a side would burn a whole run discovering the guess; failing is honest. */
    @Test
    void anUnavailableClassifierFailsTheSubmissionInsteadOfGuessing() {
        assertThatThrownBy(() -> service().create(actor(), TRACE, "key-1", request(null)))
                .isInstanceOf(CodingJobLifecycleException.class)
                .hasMessageContaining("AI 통로");
        verify(runner, never()).enqueue(any(), any());
    }

    /** The second leg of a split arrives with the repository decided; no model is asked. */
    @Test
    void anExplicitRepositoryNeverAsksTheClassifier() {
        activeProfile();
        runnerAnswers(DEV_SHA);
        when(commands.create(any(), any(), any(), any(), any())).thenReturn(created());

        service().create(actor(), TRACE, "key-1", request("backend"));

        verify(turns, never()).executeNaturalCms(any());
    }

    @Test
    void refusesARequestIntoARepositoryTheFenceLeavesClosed() {
        when(guardrail.closedTo("frontend")).thenReturn(true);

        assertThatThrownBy(() -> service().create(actor(), TRACE, "key-1", request("frontend")))
                .isInstanceOf(CodingJobLifecycleException.class)
                .hasMessageContaining("울타리");
        verify(runner, never()).enqueue(any(), any());
    }

    @Test
    void refusesARepositoryTheRunnerCannotCheckOut() {
        // The name decides which checkout the runner prepares, so an unknown one stops before
        // the runner is asked for anything at all.
        assertThatThrownBy(() -> service().create(actor(), TRACE, "key-1", request("docs")))
                .isInstanceOf(CodingJobLifecycleException.class)
                .hasMessageContaining("저장소");
        verify(runner, never()).enqueue(any(), any());
    }

    @Test
    void refusesAnEmptyRequestBeforeAskingTheRunner() {
        assertThatThrownBy(() -> service().create(actor(), TRACE, "key-1",
                new CodingConsoleContract.CreateJobRequest("backend", "   ")))
                .isInstanceOf(CodingJobLifecycleException.class)
                .hasMessageContaining("요청 내용");
        verify(runner, never()).enqueue(any(), any());
    }

    @Test
    void saysSoWhenNoProfileIsActive() {
        when(profiles.findAll("LLM_OPS")).thenReturn(List.of(
                new ProfileVersionRepository.AdminStoredProfileVersion(
                        PROFILE, "LLM_OPS", 6, "DRAFT", NOW,
                        new ObjectMapper().createObjectNode())));

        assertThatThrownBy(() -> service().create(actor(), TRACE, "key-1", request("backend")))
                .isInstanceOf(CodingJobLifecycleException.class)
                .hasMessageContaining("활성화된 AI 설정");
        verify(runner, never()).enqueue(any(), any());
    }
}

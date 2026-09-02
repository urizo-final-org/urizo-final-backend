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
import org.urizo.axmodulestudio.backend.auth.entity.AdminRole;
import org.urizo.axmodulestudio.backend.auth.security.AuthenticatedActor;
import org.urizo.axmodulestudio.backend.coding.dto.CodingConsoleContract;
import org.urizo.axmodulestudio.backend.coding.dto.CodingHandlerContract;
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

    private final ObjectMapper mapper = new ObjectMapper();
    private final CodingHandlerCommandService commands = mock(CodingHandlerCommandService.class);
    private final CodingRunnerService runner = mock(CodingRunnerService.class);
    private final ProfileVersionRepository profiles = mock(ProfileVersionRepository.class);

    private CodingJobIntakeService service() {
        return new CodingJobIntakeService(
                commands, runner, profiles, mapper, Clock.fixed(NOW, ZoneOffset.UTC),
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

    private void runnerAnswers(String sha) {
        UUID taskId = UUID.fromString("99999999-9999-4999-8999-999999999999");
        when(runner.enqueue(eq("PREPARE_SCAN_WORKTREE"), any(JsonNode.class))).thenReturn(taskId);
        ObjectNode result = new ObjectMapper().createObjectNode()
                .put("repo", "backend").put("sha", sha);
        when(runner.taskOutcome(taskId, "PREPARE_SCAN_WORKTREE"))
                .thenReturn(new CodingRunnerService.TaskOutcome("SUCCEEDED", null, result));
    }

    private static CodingConsoleContract.CreateJobRequest request(String repository) {
        return new CodingConsoleContract.CreateJobRequest(
                repository, "회원 목록에 가입일도 보이게 해줘");
    }

    @Test
    void fillsTheContractFromTheActiveProfileAndTheRunnersRealHead() {
        activeProfile();
        runnerAnswers(DEV_SHA);
        when(commands.create(any(), any(), any(), any())).thenReturn(null);

        service().create(actor(), TRACE, "key-1", request("backend"));

        ArgumentCaptor<CodingHandlerContract.CreateCodingJobRequest> sent =
                ArgumentCaptor.forClass(CodingHandlerContract.CreateCodingJobRequest.class);
        verify(commands).create(any(), eq(TRACE), eq("key-1"), sent.capture());
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
        verify(commands, never()).create(any(), any(), any(), any());
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
        verify(commands, never()).create(any(), any(), any(), any());
    }

    @Test
    void refusesTheFrontendBeforeSpendingAnythingOnIt() {
        // runner.ps1 can check out and preview the frontend but its TEST command for it is
        // unimplemented, so the Job would stop before the preview a human must approve.
        assertThatThrownBy(() -> service().create(actor(), TRACE, "key-1", request("frontend")))
                .isInstanceOf(CodingJobLifecycleException.class)
                .hasMessageContaining("backend");
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

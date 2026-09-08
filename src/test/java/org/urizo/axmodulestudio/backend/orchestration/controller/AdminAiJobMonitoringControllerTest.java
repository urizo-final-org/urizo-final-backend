package org.urizo.axmodulestudio.backend.orchestration.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.urizo.axmodulestudio.backend.auth.config.JwtProperties;
import org.urizo.axmodulestudio.backend.auth.entity.AdminRole;
import org.urizo.axmodulestudio.backend.auth.security.AuthenticatedActor;
import org.urizo.axmodulestudio.backend.auth.security.JwtTokenProvider;
import org.urizo.axmodulestudio.backend.auth.security.SecurityConfig;
import org.urizo.axmodulestudio.backend.auth.service.AuthService;
import org.urizo.axmodulestudio.backend.integration.ai.observability.LangfuseObservabilityService;
import org.urizo.axmodulestudio.backend.orchestration.monitoring.AiJobMonitoringContract.CurrentNode;
import org.urizo.axmodulestudio.backend.orchestration.monitoring.AiJobMonitoringContract.JobListResponse;
import org.urizo.axmodulestudio.backend.orchestration.monitoring.AiJobMonitoringContract.JobSnapshotResponse;
import org.urizo.axmodulestudio.backend.orchestration.monitoring.AiJobMonitoringContract.JobSummary;
import org.urizo.axmodulestudio.backend.orchestration.monitoring.AiJobMonitoringContract.LatestNodeState;
import org.urizo.axmodulestudio.backend.orchestration.monitoring.AiJobMonitoringContract.NodeDisplayStatus;
import org.urizo.axmodulestudio.backend.orchestration.monitoring.AiJobMonitoringContract.NodeOccurrence;
import org.urizo.axmodulestudio.backend.orchestration.monitoring.AiJobMonitoringContract.NodeStatus;
import org.urizo.axmodulestudio.backend.orchestration.monitoring.AiJobMonitoringService;

@WebMvcTest(
        controllers = AdminAiJobMonitoringController.class,
        properties = "ax.coding.model-turn-bridge.enabled=true")
@ActiveProfiles("local-full")
@Import(SecurityConfig.class)
class AdminAiJobMonitoringControllerTest {

    private static final UUID ACTOR_ID =
            UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID JOB_ID =
            UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID TRACE_ID =
            UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID PROFILE_VERSION_ID =
            UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final Instant NOW = Instant.parse("2026-09-07T08:00:00Z");
    private static final String TOKEN = "monitoring-controller-test-token";

    @Autowired private MockMvc mockMvc;
    @MockitoBean private AiJobMonitoringService monitoring;
    @MockitoBean private LangfuseObservabilityService observability;
    @MockitoBean private AuthService authService;
    @MockitoBean(name = "authJwtSigningKey") private SecretKey authJwtSigningKey;
    @MockitoBean private JwtEncoder jwtEncoder;
    @MockitoBean(name = "accessJwtDecoder") private JwtDecoder accessJwtDecoder;
    @MockitoBean(name = "refreshJwtDecoder") private JwtDecoder refreshJwtDecoder;
    @MockitoBean private JwtTokenProvider jwtTokenProvider;
    @MockitoBean private JwtProperties jwtProperties;

    @Test
    void superAdminReadsTheFixedListSnapshotAndSelectedObservationRoutes()
            throws Exception {
        authenticate(AdminRole.SUPER_ADMIN);
        JobSummary summary = summary();
        LatestNodeState notStarted = new LatestNodeState(
                "finish", "end", "fixture.end", NodeDisplayStatus.NOT_STARTED,
                null, null, null, null);
        NodeOccurrence occurrence = occurrence();
        when(monitoring.list(25)).thenReturn(
                new JobListResponse("1.0", NOW, List.of(summary)));
        when(monitoring.snapshot(JOB_ID, 20)).thenReturn(
                new JobSnapshotResponse(
                        "1.0", NOW, summary, List.of(notStarted),
                        List.of(occurrence), false));
        when(monitoring.requireOccurrence(JOB_ID, 2, 3, 7)).thenReturn(occurrence);
        when(observability.selectedObservations(
                JOB_ID.toString(), TRACE_ID.toString(), PROFILE_VERSION_ID.toString(),
                2, 3, "analyze", 7, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                NOW.minusSeconds(5), NOW)).thenReturn(
                        new LangfuseObservabilityService.SelectedObservationsResponse(
                                LangfuseObservabilityService.Availability.AVAILABLE,
                                null, JOB_ID.toString(), TRACE_ID.toString(),
                                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                                PROFILE_VERSION_ID.toString(), 2, 3, "analyze", 7,
                                NOW.minusSeconds(65), NOW.plusSeconds(60),
                                "local", List.of(), false));

        mockMvc.perform(get("/api/admin/ai/monitoring/jobs")
                        .queryParam("limit", "25")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobs[0].jobId").value(JOB_ID.toString()))
                .andExpect(jsonPath("$.jobs[0].domainJobStatus").value("RUNNING"))
                .andExpect(jsonPath("$.jobs[0].monitorStatus").value("RUNNING"))
                .andExpect(jsonPath("$.jobs[0].currentNode.nodeSequence").value(7))
                .andExpect(jsonPath("$.jobs[0].profileSnapshotPath")
                        .value("/api/admin/ai/profile-versions?profileKey=LLM_OPS"))
                .andExpect(jsonPath("$.jobs[0].profileLayoutPath")
                        .value("/api/admin/ai/profile-versions/" + PROFILE_VERSION_ID
                                + "/editor-layout"));

        mockMvc.perform(get("/api/admin/ai/monitoring/jobs/{jobId}", JOB_ID)
                        .queryParam("limit", "20")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latestNodeStates[0].status")
                        .value("NOT_STARTED"))
                .andExpect(jsonPath("$.occurrences[0].nodeId").value("analyze"))
                .andExpect(jsonPath("$.truncated").value(false));

        mockMvc.perform(get("/api/admin/ai/monitoring/jobs/{jobId}/occurrences/"
                        + "{pipelineAttempt}/{executionAttempt}/{nodeSequence}/observations",
                        JOB_ID, 2, 3, 7)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.jobId").value(JOB_ID.toString()))
                .andExpect(jsonPath("$.traceId").value(TRACE_ID.toString()))
                .andExpect(jsonPath("$.observationTraceId")
                        .value("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
                .andExpect(jsonPath("$.nodeSequence").value(7));

        verify(observability).selectedObservations(
                JOB_ID.toString(), TRACE_ID.toString(), PROFILE_VERSION_ID.toString(),
                2, 3, "analyze", 7, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                NOW.minusSeconds(5), NOW);
    }

    @Test
    void generalAdminIsForbiddenBeforeEitherMonitoringService() throws Exception {
        authenticate(AdminRole.GENERAL_ADMIN);

        mockMvc.perform(get("/api/admin/ai/monitoring/jobs")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        verifyNoInteractions(monitoring, observability);
    }

    private static JobSummary summary() {
        return new JobSummary(
                JOB_ID, TRACE_ID, PROFILE_VERSION_ID, "LLM_OPS", 4,
                "RUNNING", false, 12, 2, 3, NodeStatus.RUNNING, 9,
                new CurrentNode(
                        "analyze", 7, "check", "fixture.analyze", NodeStatus.RUNNING),
                "/api/admin/ai/profile-versions?profileKey=LLM_OPS",
                "/api/admin/ai/profile-versions/" + PROFILE_VERSION_ID + "/editor-layout",
                NOW);
    }

    private static NodeOccurrence occurrence() {
        return new NodeOccurrence(
                JOB_ID, PROFILE_VERSION_ID, 2, 3, "analyze", 7, TRACE_ID,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "check", "fixture.analyze",
                NodeStatus.COMPLETED, NOW.minusSeconds(5), null, NOW, null, null,
                "/api/admin/ai/monitoring/jobs/" + JOB_ID
                        + "/occurrences/2/3/7/observations",
                NOW);
    }

    private void authenticate(AdminRole role) {
        Jwt jwt = Jwt.withTokenValue(TOKEN)
                .header("alg", "HS256")
                .subject(ACTOR_ID.toString())
                .claim("token_type", "access")
                .build();
        when(accessJwtDecoder.decode(TOKEN)).thenReturn(jwt);
        when(authService.loadActor(ACTOR_ID)).thenReturn(
                new AuthenticatedActor(ACTOR_ID, "관리자", role));
    }
}

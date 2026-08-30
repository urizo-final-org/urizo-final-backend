package org.urizo.axmodulestudio.backend.coding.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.urizo.axmodulestudio.backend.auth.security.SecurityConfig;
import org.urizo.axmodulestudio.backend.coding.dto.CodingWorkerContract;
import org.urizo.axmodulestudio.backend.coding.service.CodingWorkerService;

@WebMvcTest(
        controllers = CodingWorkerController.class,
        properties = "ax.coding.model-turn-bridge.enabled=true")
@Import(SecurityConfig.class)
class CodingWorkerControllerTest {

    private static final UUID JOB_ID =
            UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID TRACE_ID =
            UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final UUID PROFILE_VERSION_ID =
            UUID.fromString("77777777-7777-4777-8777-777777777777");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private CodingWorkerService service;

    @Test
    void resolvesTheDatabaseAuthoritativeClaimContextWithoutSnapshotContent() throws Exception {
        CodingWorkerContract.ClaimContextResponse context =
                new CodingWorkerContract.ClaimContextResponse(
                        "1.0",
                        UUID.fromString("66666666-6666-4666-8666-666666666666"),
                        "CODING_JOB_REQUESTED",
                        JOB_ID,
                        TRACE_ID,
                        "coding-job:" + JOB_ID + ":v4",
                        1,
                        4,
                        Instant.parse("2026-08-11T11:00:00Z"),
                        PROFILE_VERSION_ID,
                        1,
                        1,
                        null,
                        null,
                        new CodingWorkerContract.JobPayload(
                                UUID.fromString("11111111-1111-4111-8111-111111111111"),
                                UUID.fromString("22222222-2222-4222-8222-222222222222"),
                                UUID.fromString("33333333-3333-4333-8333-333333333333"),
                                "plan",
                                "sha1:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                                "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                                "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                                Instant.parse("2026-08-11T12:00:00Z")));
        when(service.claimContext("Bearer local-service-test-token", JOB_ID))
                .thenReturn(context);

        mockMvc.perform(get("/internal/coding/worker/jobs/{jobId}/claim-context", JOB_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer local-service-test-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value(JOB_ID.toString()))
                .andExpect(jsonPath("$.profileVersionId").value(PROFILE_VERSION_ID.toString()))
                .andExpect(jsonPath("$.expectedStateVersion").value(4))
                .andExpect(jsonPath("$.pipelineAttempt").value(1))
                .andExpect(jsonPath("$.executionAttempt").value(1))
                .andExpect(jsonPath("$.workspaceId").isEmpty())
                .andExpect(jsonPath("$.toolCallId").isEmpty())
                .andExpect(jsonPath("$.snapshot").doesNotExist());
    }

    @Test
    void storeFailureBeforeResolutionUsesRetryablePreContextEnvelope() throws Exception {
        when(service.claimContext("Bearer local-service-test-token", JOB_ID))
                .thenThrow(new DataAccessResourceFailureException("test store unavailable"));

        mockMvc.perform(get("/internal/coding/worker/jobs/{jobId}/claim-context", JOB_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer local-service-test-token"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.requestId").isString())
                .andExpect(jsonPath("$.jobId").doesNotExist())
                .andExpect(jsonPath("$.idempotencyKey").doesNotExist())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_TRANSIENT_ERROR"))
                .andExpect(jsonPath("$.error.retryable").value(true));
    }

    @Test
    void claimReturnsTheSameImmutableProfileVersionBinding() throws Exception {
        CodingWorkerContract.ClaimRequest request = new CodingWorkerContract.ClaimRequest(
                "1.0",
                UUID.fromString("66666666-6666-4666-8666-666666666666"),
                JOB_ID,
                TRACE_ID,
                "coding-job:" + JOB_ID + ":v4",
                1,
                4);
        UUID leaseId = UUID.fromString("99999999-9999-4999-8999-999999999999");
        CodingWorkerContract.Snapshot snapshot = new CodingWorkerContract.Snapshot(
                new CodingWorkerContract.Actor(
                        UUID.fromString("11111111-1111-4111-8111-111111111111"),
                        "DEVELOPER"),
                new CodingWorkerContract.Project(
                        UUID.fromString("22222222-2222-4222-8222-222222222222")),
                new CodingWorkerContract.Repository(
                        UUID.fromString("33333333-3333-4333-8333-333333333333")),
                "plan",
                "sha1:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                "coding-plan-v1",
                List.of("CHAT"),
                List.of("plan"),
                Instant.parse("2026-08-11T11:02:00Z"),
                "system",
                "user",
                "README.md",
                UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"));
        when(service.claim("Bearer local-service-test-token", request)).thenReturn(
                new CodingWorkerContract.ClaimResponse(
                        "1.0", JOB_ID, TRACE_ID, PROFILE_VERSION_ID, leaseId,
                        Instant.parse("2026-08-11T11:00:30Z"), 5, false, snapshot));

        mockMvc.perform(post("/internal/coding/worker/jobs/{jobId}/claim", JOB_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer local-service-test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value(JOB_ID.toString()))
                .andExpect(jsonPath("$.profileVersionId").value(PROFILE_VERSION_ID.toString()))
                .andExpect(jsonPath("$.snapshot.actor.actorId")
                        .value("11111111-1111-4111-8111-111111111111"));
    }
}

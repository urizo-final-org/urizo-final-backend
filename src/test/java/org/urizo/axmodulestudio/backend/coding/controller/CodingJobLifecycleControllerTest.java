package org.urizo.axmodulestudio.backend.coding.controller;

import org.urizo.axmodulestudio.backend.coding.dto.CodingJobLifecycleContract;
import org.urizo.axmodulestudio.backend.coding.service.CodingJobLifecycleService;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.urizo.axmodulestudio.backend.integration.ai.local.LocalDevRequestGuard;
import org.urizo.axmodulestudio.backend.auth.security.SecurityConfig;

@WebMvcTest(
        controllers = CodingJobLifecycleController.class,
        properties = "ax.coding.job-lifecycle.enabled=true")
@ActiveProfiles({"dev", "coding-job-local-fixture"})
@Import(SecurityConfig.class)
class CodingJobLifecycleControllerTest {

    private static final UUID TRACE_ID = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final UUID JOB_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private CodingJobLifecycleService service;

    @MockitoBean
    private LocalDevRequestGuard requestGuard;

    @Test
    void createsAndTransitionsOnlyThroughTheOptInLocalBoundary() throws Exception {
        CodingJobLifecycleContract.JobResponse pending = response(
                CodingJobLifecycleContract.Status.PENDING, 1, null);
        CodingJobLifecycleContract.JobResponse running = response(
                CodingJobLifecycleContract.Status.RUNNING, 2, Instant.parse("2026-08-11T11:01:00Z"));
        when(service.create(eq(TRACE_ID), eq("job.create.0001"), any())).thenReturn(pending);
        when(service.transition(eq(JOB_ID), eq(TRACE_ID), eq("job.transition.0001"), any()))
                .thenReturn(running);

        mockMvc.perform(post("/internal/dev/coding-jobs")
                        .header("X-Trace-Id", TRACE_ID)
                        .header("Idempotency-Key", "job.create.0001")
                        .header("Origin", "http://127.0.0.1:5173")
                        .header("X-AXMS-CSRF", "local-test-proof")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(createRequest())))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/internal/dev/coding-jobs/" + JOB_ID))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.stateVersion").value(1))
                .andExpect(jsonPath("$.contextDigest").doesNotExist())
                .andExpect(jsonPath("$.policyHash").doesNotExist());

        mockMvc.perform(post("/internal/dev/coding-jobs/{jobId}/transitions", JOB_ID)
                        .header("X-Trace-Id", TRACE_ID)
                        .header("Idempotency-Key", "job.transition.0001")
                        .header("Origin", "http://127.0.0.1:5173")
                        .header("X-AXMS-CSRF", "local-test-proof")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"schemaVersion":"1.0","expectedStateVersion":1,"targetStatus":"RUNNING"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.stateVersion").value(2));
    }

    @Test
    void rejectsUnknownFieldsBeforeTheOwnerService() throws Exception {
        ObjectNode body = objectMapper.valueToTree(createRequest());
        body.put("provider", "OPENAI");

        mockMvc.perform(post("/internal/dev/coding-jobs")
                        .header("X-Trace-Id", TRACE_ID)
                        .header("Idempotency-Key", "job.create.0002")
                        .header("Origin", "http://127.0.0.1:5173")
                        .header("X-AXMS-CSRF", "local-test-proof")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("CONTRACT_VALIDATION_FAILED"));
        verifyNoInteractions(service);
    }

    @Test
    void readsOnlyWithinTheAuthoritativeTraceScope() throws Exception {
        when(service.find(JOB_ID, TRACE_ID)).thenReturn(response(
                CodingJobLifecycleContract.Status.PENDING, 1, null));

        mockMvc.perform(get("/internal/dev/coding-jobs/{jobId}", JOB_ID)
                        .header("X-Trace-Id", TRACE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value(JOB_ID.toString()))
                .andExpect(jsonPath("$.traceId").value(TRACE_ID.toString()));
    }

    private static CodingJobLifecycleContract.CreateRequest createRequest() {
        return new CodingJobLifecycleContract.CreateRequest(
                "1.0",
                UUID.fromString("11111111-1111-4111-8111-111111111111"),
                UUID.fromString("22222222-2222-4222-8222-222222222222"),
                UUID.fromString("33333333-3333-4333-8333-333333333333"),
                "plan",
                "sha1:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                "coding-plan-v1",
                List.of("CHAT"),
                List.of("plan"),
                Instant.parse("2026-08-11T12:00:00Z"));
    }

    private static CodingJobLifecycleContract.JobResponse response(
            CodingJobLifecycleContract.Status status,
            int version,
            Instant startedAt) {
        CodingJobLifecycleContract.CreateRequest request = createRequest();
        return new CodingJobLifecycleContract.JobResponse(
                "1.0",
                JOB_ID,
                TRACE_ID,
                request.actorId(),
                request.projectId(),
                request.repositoryId(),
                request.graphStep(),
                status,
                version,
                request.promptVersion(),
                request.allowedCapabilities(),
                request.allowedNodes(),
                request.expiresAt(),
                Instant.parse("2026-08-11T11:00:00Z"),
                startedAt,
                startedAt == null ? Instant.parse("2026-08-11T11:00:00Z") : startedAt,
                null,
                null);
    }
}

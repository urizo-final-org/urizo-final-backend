package org.urizo.axmodulestudio.backend.orchestration.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.urizo.axmodulestudio.backend.auth.security.SecurityConfig;
import org.urizo.axmodulestudio.backend.orchestration.monitoring.AiJobMonitoringContract.NodeOccurrenceReport;
import org.urizo.axmodulestudio.backend.orchestration.monitoring.AiJobMonitoringContract.NodeStatus;
import org.urizo.axmodulestudio.backend.orchestration.monitoring.AiJobMonitoringContract.ReportResponse;
import org.urizo.axmodulestudio.backend.orchestration.monitoring.AiJobMonitoringService;

@WebMvcTest(
        controllers = InternalAiJobMonitoringController.class,
        properties = "ax.coding.model-turn-bridge.enabled=true")
@Import(SecurityConfig.class)
class InternalAiJobMonitoringControllerTest {

    private static final UUID JOB_ID =
            UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID TRACE_ID =
            UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID PROFILE_VERSION_ID =
            UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final Instant NOW = Instant.parse("2026-09-07T08:00:00Z");

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockitoBean private AiJobMonitoringService service;

    @Test
    void postsTheExactBoundedOccurrenceContract() throws Exception {
        NodeOccurrenceReport report = report();
        when(service.report("Bearer service-token", report)).thenReturn(
                new ReportResponse("1.0", 7, true, true, NodeStatus.RUNNING, NOW));

        mockMvc.perform(post("/internal/ai/monitoring/node-occurrences")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer service-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(report)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value("1.0"))
                .andExpect(jsonPath("$.monitorRevision").value(7))
                .andExpect(jsonPath("$.applied").value(true))
                .andExpect(jsonPath("$.current").value(true))
                .andExpect(jsonPath("$.status").value("RUNNING"));

        verify(service).report("Bearer service-token", report);
    }

    @Test
    void rejectsAnInvalidStatusErrorPairBeforeTheService() throws Exception {
        ObjectNode invalid = objectMapper.valueToTree(report());
        invalid.put("status", "FAILED");
        invalid.putNull("errorCode");

        mockMvc.perform(post("/internal/ai/monitoring/node-occurrences")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer service-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(invalid)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("CONTRACT_VALIDATION_FAILED"));

        verifyNoInteractions(service);
    }

    private static NodeOccurrenceReport report() {
        return new NodeOccurrenceReport(
                "1.0", JOB_ID, TRACE_ID, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                PROFILE_VERSION_ID, 2, 3, "analyze", 7,
                "check", "fixture.analyze", NodeStatus.RUNNING, NOW, null);
    }
}

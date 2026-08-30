package org.urizo.axmodulestudio.backend.coding.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.urizo.axmodulestudio.backend.auth.security.SecurityConfig;
import org.urizo.axmodulestudio.backend.coding.dto.CodingHandlerContract;
import org.urizo.axmodulestudio.backend.coding.service.CodingHandlerStageService;

@WebMvcTest(
        controllers = CodingHandlerStageController.class,
        properties = "ax.coding.model-turn-bridge.enabled=true")
@Import(SecurityConfig.class)
class CodingHandlerStageControllerTest {

    private static final UUID JOB =
            UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final UUID TRACE =
            UUID.fromString("66666666-6666-4666-8666-666666666666");
    private static final UUID RESULT =
            UUID.fromString("77777777-7777-4777-8777-777777777777");
    private static final UUID WORKSPACE =
            UUID.fromString("88888888-8888-4888-8888-888888888888");
    private static final String CANDIDATE =
            "sha1:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String DIFF =
            "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private CodingHandlerStageService service;

    @Test
    void returnsTheStrictAi04StageResult() throws Exception {
        CodingHandlerContract.StageExecutionRequest request =
                new CodingHandlerContract.StageExecutionRequest(
                        "1.0", TRACE, 4, 2, "coding.code", RESULT);
        when(service.execute(
                eq("Bearer local-service-test-token"), eq(JOB), eq(1),
                eq(RESULT), any())).thenReturn(
                        new CodingHandlerContract.StageExecutionResponse(
                                "1.0", RESULT, "coding.code", "completed",
                                WORKSPACE, CANDIDATE, DIFF, null,
                                JsonNodeFactory.instance.objectNode().put("status", "DONE")));

        mockMvc.perform(post(
                        "/internal/coding/worker/jobs/{jobId}/attempts/{attempt}/"
                                + "stages/{handlerKey}/executions/{resultId}",
                        JOB, 1, "coding.code", RESULT)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer local-service-test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultId").value(RESULT.toString()))
                .andExpect(jsonPath("$.handlerKey").value("coding.code"))
                .andExpect(jsonPath("$.resultPort").value("completed"))
                .andExpect(jsonPath("$.workspaceId").value(WORKSPACE.toString()))
                .andExpect(jsonPath("$.candidateSha").value(CANDIDATE))
                .andExpect(jsonPath("$.diffDigest").value(DIFF));
    }

    @Test
    void rejectsHandlerPathAndBodyDriftBeforeStageExecution() throws Exception {
        CodingHandlerContract.StageExecutionRequest request =
                new CodingHandlerContract.StageExecutionRequest(
                        "1.0", TRACE, 4, 2, "coding.review", RESULT);

        mockMvc.perform(post(
                        "/internal/coding/worker/jobs/{jobId}/attempts/{attempt}/"
                                + "stages/{handlerKey}/executions/{resultId}",
                        JOB, 1, "coding.code", RESULT)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer local-service-test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code")
                        .value("CONTRACT_VALIDATION_FAILED"));
    }
}

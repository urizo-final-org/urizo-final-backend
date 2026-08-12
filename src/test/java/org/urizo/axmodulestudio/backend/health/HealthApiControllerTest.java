package org.urizo.axmodulestudio.backend.health;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class HealthApiControllerTest {

    private static final String UUID_PATTERN =
            "[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void healthCreatesAndReturnsATraceId() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Trace-Id", matchesPattern(UUID_PATTERN)))
                .andExpect(jsonPath("$.schemaVersion").value("1.0"))
                .andExpect(jsonPath("$.traceId", matchesPattern(UUID_PATTERN)))
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.checkedAt").exists());
    }

    @Test
    void healthPreservesAValidSuppliedTraceId() throws Exception {
        String traceId = "c1730c85-0f24-4a63-8f61-88835834f027";

        mockMvc.perform(get("/api/health").header("X-Trace-Id", traceId))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Trace-Id", traceId))
                .andExpect(jsonPath("$.traceId").value(traceId));
    }

    @Test
    void invalidTraceIdIsRejectedWithThePublicErrorEnvelope() throws Exception {
        mockMvc.perform(get("/api/health").header("X-Trace-Id", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("X-Trace-Id", matchesPattern(UUID_PATTERN)))
                .andExpect(jsonPath("$.schemaVersion").value("1.0"))
                .andExpect(jsonPath("$.traceId", matchesPattern(UUID_PATTERN)))
                .andExpect(jsonPath("$.error.code").value("INVALID_TRACE_ID"))
                .andExpect(jsonPath("$.error.retryable").value(false));
    }

    @Test
    void readinessIsHonestWhileRequiredInfrastructureIsDisabled() throws Exception {
        mockMvc.perform(get("/api/readiness"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("X-Trace-Id", matchesPattern(UUID_PATTERN)))
                .andExpect(jsonPath("$.schemaVersion").value("1.0"))
                .andExpect(jsonPath("$.status").value("NOT_READY"))
                .andExpect(jsonPath("$.checks", hasSize(3)))
                .andExpect(jsonPath("$.checks[0].required").value(true));
    }
}

package org.urizo.axmodulestudio.backend.orchestration.controller;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.urizo.axmodulestudio.backend.auth.security.SecurityConfig;
import org.urizo.axmodulestudio.backend.orchestration.service.ProfileVersionException;
import org.urizo.axmodulestudio.backend.orchestration.service.ProfileVersionService;

@WebMvcTest(
        controllers = ProfileVersionController.class,
        properties = "ax.coding.model-turn-bridge.enabled=true")
@Import(SecurityConfig.class)
class ProfileVersionControllerTest {

    private static final UUID PROFILE_VERSION_ID =
            UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID TRACE_ID =
            UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final String AUTHORIZATION = "Bearer local-service-test-token";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ProfileVersionService service;

    @Test
    void returnsTheExecutableSnapshotWithoutAnEnvelope() throws Exception {
        JsonNode snapshot = objectMapper.readTree(Files.readString(Path.of(
                "contracts/fixtures/orchestration/profile-version.snapshot.valid.json")));
        when(service.getBound(AUTHORIZATION, PROFILE_VERSION_ID)).thenReturn(snapshot);

        mockMvc.perform(get("/internal/ai/profile-versions/{profileVersionId}", PROFILE_VERSION_ID)
                        .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                        .header("X-Trace-Id", TRACE_ID))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.contractVersion").value("1.0"))
                .andExpect(jsonPath("$.profileVersionId").value(PROFILE_VERSION_ID.toString()))
                .andExpect(jsonPath("$.profileKey").value("LLM_OPS"))
                .andExpect(jsonPath("$.profileVersion").value(1))
                .andExpect(jsonPath("$.snapshot").doesNotExist())
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    void missingVersionUsesThePreContextNotFoundEnvelope() throws Exception {
        when(service.getBound(AUTHORIZATION, PROFILE_VERSION_ID)).thenThrow(
                failure("PROFILE_VERSION_NOT_FOUND", HttpStatus.NOT_FOUND));

        mockMvc.perform(get("/internal/ai/profile-versions/{profileVersionId}", PROFILE_VERSION_ID)
                        .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                        .header("X-Trace-Id", TRACE_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.schemaVersion").value("1.0"))
                .andExpect(jsonPath("$.requestId").isNotEmpty())
                .andExpect(jsonPath("$.traceId").value(TRACE_ID.toString()))
                .andExpect(jsonPath("$.error.code").value("PROFILE_VERSION_NOT_FOUND"))
                .andExpect(jsonPath("$.error.retryable").value(false))
                .andExpect(jsonPath("$.error.executionState").value("NOT_STARTED"))
                .andExpect(jsonPath("$.error.retryAfterMs").doesNotExist());
    }

    @Test
    void nonExecutableVersionUsesConflict() throws Exception {
        when(service.getBound(AUTHORIZATION, PROFILE_VERSION_ID)).thenThrow(
                failure("PROFILE_VERSION_NOT_ACTIVE", HttpStatus.CONFLICT));

        mockMvc.perform(get("/internal/ai/profile-versions/{profileVersionId}", PROFILE_VERSION_ID)
                        .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PROFILE_VERSION_NOT_ACTIVE"))
                .andExpect(jsonPath("$.error.retryable").value(false));
    }

    @Test
    void missingCredentialUsesBearerChallengeWithoutEchoingSecrets() throws Exception {
        when(service.getBound(isNull(), eq(PROFILE_VERSION_ID))).thenThrow(
                failure("SERVICE_AUTHENTICATION_FAILED", HttpStatus.UNAUTHORIZED));

        mockMvc.perform(get("/internal/ai/profile-versions/{profileVersionId}", PROFILE_VERSION_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(jsonPath("$.error.code").value("SERVICE_AUTHENTICATION_FAILED"))
                .andExpect(content().string(not(containsString("local-service-test-token"))));
    }

    @Test
    void transientStoreFailureUsesRetryMetadata() throws Exception {
        when(service.getBound(AUTHORIZATION, PROFILE_VERSION_ID)).thenThrow(
                new ProfileVersionException(
                        "INTERNAL_TRANSIENT_ERROR",
                        "The AI Profile Version store is temporarily unavailable.",
                        HttpStatus.SERVICE_UNAVAILABLE,
                        true,
                        1_000L));

        mockMvc.perform(get("/internal/ai/profile-versions/{profileVersionId}", PROFILE_VERSION_ID)
                        .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_TRANSIENT_ERROR"))
                .andExpect(jsonPath("$.error.retryable").value(true))
                .andExpect(jsonPath("$.error.retryAfterMs").value(1_000));
    }

    @Test
    void malformedProfileVersionIdIsRejectedBeforeTheService() throws Exception {
        mockMvc.perform(get("/internal/ai/profile-versions/not-a-uuid")
                        .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("CONTRACT_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.retryable").value(false));
        verifyNoInteractions(service);
    }

    private static ProfileVersionException failure(String code, HttpStatus status) {
        return new ProfileVersionException(code, "Contract failure.", status);
    }
}

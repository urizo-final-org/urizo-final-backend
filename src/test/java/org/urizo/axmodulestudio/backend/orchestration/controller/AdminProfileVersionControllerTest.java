package org.urizo.axmodulestudio.backend.orchestration.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import javax.crypto.SecretKey;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.urizo.axmodulestudio.backend.auth.security.JwtTokenProvider;
import org.urizo.axmodulestudio.backend.auth.security.SecurityConfig;
import org.urizo.axmodulestudio.backend.auth.security.AuthenticatedActor;
import org.urizo.axmodulestudio.backend.auth.config.JwtProperties;
import org.urizo.axmodulestudio.backend.auth.entity.AdminRole;
import org.urizo.axmodulestudio.backend.auth.service.AuthService;
import org.urizo.axmodulestudio.backend.orchestration.repository.ProfileVersionRepository;
import org.urizo.axmodulestudio.backend.orchestration.service.ProfileVersionException;
import org.urizo.axmodulestudio.backend.orchestration.service.ProfileVersionService;

@WebMvcTest(
        controllers = AdminProfileVersionController.class,
        properties = "ax.coding.model-turn-bridge.enabled=true")
@ActiveProfiles("local-full")
@Import(SecurityConfig.class)
class AdminProfileVersionControllerTest {

    private static final UUID PROFILE_VERSION_ID =
            UUID.fromString("77777777-7777-4777-8777-777777777777");
    private static final UUID TRACE_ID =
            UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final UUID ACTOR_ID =
            UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final String ACCESS_TOKEN = "admin-profile-test-token";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ProfileVersionService service;

    @MockitoBean
    private AuthService authService;

    @MockitoBean(name = "authJwtSigningKey")
    private SecretKey authJwtSigningKey;

    @MockitoBean
    private JwtEncoder jwtEncoder;

    @MockitoBean(name = "accessJwtDecoder")
    private JwtDecoder accessJwtDecoder;

    @MockitoBean(name = "refreshJwtDecoder")
    private JwtDecoder refreshJwtDecoder;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private JwtProperties jwtProperties;

    @Test
    void superAdminCanListAndActivateProfileVersions() throws Exception {
        authenticate(AdminRole.SUPER_ADMIN);
        ProfileVersionRepository.AdminStoredProfileVersion stored = stored("ACTIVE");
        when(service.listAdmin("LLM_OPS")).thenReturn(List.of(stored));
        when(service.activate(PROFILE_VERSION_ID)).thenReturn(stored);

        mockMvc.perform(get("/api/admin/ai/profile-versions")
                        .queryParam("profileKey", "LLM_OPS")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
                        .header("X-Trace-Id", TRACE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].profileVersionId")
                        .value(PROFILE_VERSION_ID.toString()))
                .andExpect(jsonPath("$[0].profileKey").value("LLM_OPS"))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$[0].snapshot.guardrailProfileKey")
                        .value("central.default"));

        mockMvc.perform(post("/api/admin/ai/profile-versions/{profileVersionId}/activate",
                        PROFILE_VERSION_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
                        .header("X-Trace-Id", TRACE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void generalAdminIsForbiddenBeforeTheProfileService() throws Exception {
        authenticate(AdminRole.GENERAL_ADMIN);
        mockMvc.perform(get("/api/admin/ai/profile-versions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
                        .header("X-Trace-Id", TRACE_ID))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        verifyNoInteractions(service);
    }

    @Test
    void missingAuthenticationIsRejectedBeforeTheProfileService() throws Exception {
        mockMvc.perform(get("/api/admin/ai/profile-versions")
                        .header("X-Trace-Id", TRACE_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));
        verifyNoInteractions(service);
    }

    @Test
    void validationErrorsUseThePublicErrorEnvelope() throws Exception {
        authenticate(AdminRole.SUPER_ADMIN);
        when(service.createDraft(eq("LLM_OPS"), any())).thenThrow(
                new ProfileVersionException(
                        "CONTRACT_VALIDATION_FAILED",
                        "snapshot requires locked guardrail nodes",
                        HttpStatus.BAD_REQUEST));

        mockMvc.perform(post("/api/admin/ai/profile-versions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
                        .header("X-Trace-Id", TRACE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"profileKey":"LLM_OPS","snapshot":{}}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.schemaVersion").value("1.0"))
                .andExpect(jsonPath("$.traceId").value(TRACE_ID.toString()))
                .andExpect(jsonPath("$.error.code").value("CONTRACT_VALIDATION_FAILED"));
    }

    private void authenticate(AdminRole role) {
        Jwt jwt = Jwt.withTokenValue(ACCESS_TOKEN)
                .header("alg", "HS256")
                .subject(ACTOR_ID.toString())
                .claim("token_type", "access")
                .build();
        when(accessJwtDecoder.decode(ACCESS_TOKEN)).thenReturn(jwt);
        when(authService.loadActor(ACTOR_ID)).thenReturn(
                new AuthenticatedActor(ACTOR_ID, "관리자", role));
    }

    private ProfileVersionRepository.AdminStoredProfileVersion stored(String status) {
        ObjectNode snapshot = objectMapper.createObjectNode();
        snapshot.put("contractVersion", "1.0");
        snapshot.put("profileVersionId", PROFILE_VERSION_ID.toString());
        snapshot.put("profileKey", "LLM_OPS");
        snapshot.put("profileVersion", 2);
        snapshot.putArray("nodes").addObject()
                .put("id", "guardrail")
                .put("type", "guardrail")
                .put("handlerKey", "common.guardrail")
                .putArray("resultPorts").add("passed");
        snapshot.putArray("edges");
        snapshot.putObject("config");
        snapshot.putObject("modelBindings");
        snapshot.putObject("toolPolicy");
        snapshot.put("guardrailProfileKey", "central.default");
        return new ProfileVersionRepository.AdminStoredProfileVersion(
                PROFILE_VERSION_ID,
                "LLM_OPS",
                2,
                status,
                Instant.parse("2026-08-31T00:00:00Z"),
                snapshot);
    }
}

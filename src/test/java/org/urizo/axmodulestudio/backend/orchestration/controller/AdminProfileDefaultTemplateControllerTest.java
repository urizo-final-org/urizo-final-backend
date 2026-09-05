package org.urizo.axmodulestudio.backend.orchestration.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import javax.crypto.SecretKey;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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
import org.urizo.axmodulestudio.backend.orchestration.repository.ProfileDefaultTemplateRepository;
import org.urizo.axmodulestudio.backend.orchestration.service.ProfileDefaultTemplateService;

@WebMvcTest(
        controllers = AdminProfileDefaultTemplateController.class,
        properties = "ax.coding.model-turn-bridge.enabled=true")
@ActiveProfiles("local-full")
@Import(SecurityConfig.class)
class AdminProfileDefaultTemplateControllerTest {

    private static final UUID TRACE_ID =
            UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final UUID ACTOR_ID =
            UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final String ACCESS_TOKEN = "default-template-test-token";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockitoBean private ProfileDefaultTemplateService service;
    @MockitoBean private AuthService authService;
    @MockitoBean(name = "authJwtSigningKey") private SecretKey authJwtSigningKey;
    @MockitoBean private JwtEncoder jwtEncoder;
    @MockitoBean(name = "accessJwtDecoder") private JwtDecoder accessJwtDecoder;
    @MockitoBean(name = "refreshJwtDecoder") private JwtDecoder refreshJwtDecoder;
    @MockitoBean private JwtTokenProvider jwtTokenProvider;
    @MockitoBean private JwtProperties jwtProperties;

    @Test
    void superAdminCanLoadAndSaveOneProfileTemplate() throws Exception {
        authenticate(AdminRole.SUPER_ADMIN);
        ObjectNode snapshot = objectMapper.createObjectNode();
        snapshot.putArray("nodes");
        ProfileDefaultTemplateRepository.StoredDefaultTemplate stored =
                new ProfileDefaultTemplateRepository.StoredDefaultTemplate(
                        "LLM_OPS", Instant.parse("2026-09-03T00:00:00Z"), snapshot);
        when(service.get("LLM_OPS")).thenReturn(stored);
        when(service.save(eq("LLM_OPS"), any())).thenReturn(stored);

        mockMvc.perform(get("/api/admin/ai/profile-templates/LLM_OPS")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
                        .header("X-Trace-Id", TRACE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileKey").value("LLM_OPS"));

        mockMvc.perform(put("/api/admin/ai/profile-templates/LLM_OPS")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
                        .header("X-Trace-Id", TRACE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"snapshot\":{\"nodes\":[]}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileKey").value("LLM_OPS"));
    }

    @Test
    void generalAdminIsForbiddenBeforeTheTemplateService() throws Exception {
        authenticate(AdminRole.GENERAL_ADMIN);

        mockMvc.perform(get("/api/admin/ai/profile-templates/LLM_OPS")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
                        .header("X-Trace-Id", TRACE_ID))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        verifyNoInteractions(service);
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
}

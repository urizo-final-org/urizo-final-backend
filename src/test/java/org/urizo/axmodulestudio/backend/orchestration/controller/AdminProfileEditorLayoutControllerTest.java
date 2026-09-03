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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.urizo.axmodulestudio.backend.orchestration.repository.ProfileEditorLayoutRepository;
import org.urizo.axmodulestudio.backend.orchestration.service.ProfileEditorLayoutService;

@WebMvcTest(
        controllers = AdminProfileEditorLayoutController.class,
        properties = "ax.coding.model-turn-bridge.enabled=true")
@ActiveProfiles("local-full")
@Import(SecurityConfig.class)
class AdminProfileEditorLayoutControllerTest {

    private static final UUID PROFILE_VERSION_ID =
            UUID.fromString("77777777-7777-4777-8777-777777777777");
    private static final UUID TRACE_ID =
            UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final UUID ACTOR_ID =
            UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final String ACCESS_TOKEN = "profile-layout-test-token";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ProfileEditorLayoutService service;

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
    void superAdminCanCreateAndReadAnEditorLayout() throws Exception {
        authenticate(AdminRole.SUPER_ADMIN);
        ProfileEditorLayoutRepository.StoredEditorLayout stored = stored();
        when(service.save(eq(PROFILE_VERSION_ID), any())).thenReturn(
                new ProfileEditorLayoutRepository.SaveResult(stored, true));
        when(service.get(PROFILE_VERSION_ID)).thenReturn(stored);

        mockMvc.perform(put(
                        "/api/admin/ai/profile-versions/{profileVersionId}/editor-layout",
                        PROFILE_VERSION_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
                        .header("X-Trace-Id", TRACE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nodes":[{"id":"start","x":10,"y":20}]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.profileVersionId")
                        .value(PROFILE_VERSION_ID.toString()))
                .andExpect(jsonPath("$.nodes[0].id").value("start"))
                .andExpect(jsonPath("$.nodes[0].x").value(10));

        mockMvc.perform(get(
                        "/api/admin/ai/profile-versions/{profileVersionId}/editor-layout",
                        PROFILE_VERSION_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
                        .header("X-Trace-Id", TRACE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes[0].y").value(20));
    }

    @Test
    void identicalSaveReturnsOkInsteadOfCreatingAnotherLayout() throws Exception {
        authenticate(AdminRole.SUPER_ADMIN);
        when(service.save(eq(PROFILE_VERSION_ID), any())).thenReturn(
                new ProfileEditorLayoutRepository.SaveResult(stored(), false));

        mockMvc.perform(put(
                        "/api/admin/ai/profile-versions/{profileVersionId}/editor-layout",
                        PROFILE_VERSION_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
                        .header("X-Trace-Id", TRACE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nodes":[{"id":"start","x":10,"y":20}]}
                                """))
                .andExpect(status().isOk());
    }

    @Test
    void generalAdminIsForbiddenBeforeTheLayoutService() throws Exception {
        authenticate(AdminRole.GENERAL_ADMIN);

        mockMvc.perform(get(
                        "/api/admin/ai/profile-versions/{profileVersionId}/editor-layout",
                        PROFILE_VERSION_ID)
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

    private ProfileEditorLayoutRepository.StoredEditorLayout stored() {
        JsonNode nodes = objectMapper.createArrayNode().add(
                objectMapper.createObjectNode()
                        .put("id", "start")
                        .put("x", 10)
                        .put("y", 20));
        return new ProfileEditorLayoutRepository.StoredEditorLayout(
                PROFILE_VERSION_ID,
                Instant.parse("2026-09-03T00:00:00Z"),
                objectMapper.createObjectNode().set("nodes", nodes));
    }
}

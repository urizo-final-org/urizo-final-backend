package org.urizo.axmodulestudio.backend.orchestration.controller;

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
import org.urizo.axmodulestudio.backend.orchestration.monitoring.LocalProviderObservability;

@WebMvcTest(controllers = AdminLocalProviderObservabilityController.class)
@ActiveProfiles("local-full")
@org.springframework.test.context.TestPropertySource(properties = "ax.coding.model-turn-bridge.enabled=true")
@Import(SecurityConfig.class)
class AdminLocalProviderObservabilityControllerTest {

    private static final UUID ACTOR_ID =
            UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final String TOKEN = "langfuse-observability-test-token";
    private static final String FROM = "2026-09-01T00:00:00Z";
    private static final String TO = "2026-09-02T00:00:00Z";

    @Autowired private MockMvc mockMvc;
    @MockitoBean private LocalProviderObservability service;
    @MockitoBean private AuthService authService;
    @MockitoBean(name = "authJwtSigningKey") private SecretKey authJwtSigningKey;
    @MockitoBean private JwtEncoder jwtEncoder;
    @MockitoBean(name = "accessJwtDecoder") private JwtDecoder accessJwtDecoder;
    @MockitoBean(name = "refreshJwtDecoder") private JwtDecoder refreshJwtDecoder;
    @MockitoBean private JwtTokenProvider jwtTokenProvider;
    @MockitoBean private JwtProperties jwtProperties;

    @Test void localProviderReadsRequireSuperAdmin() throws Exception {
        for (String endpoint : List.of("metrics", "token-usage", "observations")) {
            mockMvc.perform(get("/api/admin/ai/observability/local/" + endpoint).queryParam("from", FROM).queryParam("to", TO))
                .andExpect(status().isUnauthorized());
        }
        authenticate(AdminRole.GENERAL_ADMIN);
        for (String endpoint : List.of("metrics", "token-usage", "observations")) {
            mockMvc.perform(get("/api/admin/ai/observability/local/" + endpoint).queryParam("from", FROM).queryParam("to", TO)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)).andExpect(status().isForbidden());
        }
        verifyNoInteractions(service);
    }

    @Test void localDataHasExplicitSourceAndSafeValidationErrors() throws Exception {
        authenticate(AdminRole.SUPER_ADMIN);
        when(service.metrics(FROM, TO, null)).thenReturn(new LocalProviderObservability.Metrics(
            "AVAILABLE", null, Instant.parse(FROM), Instant.parse(TO), "local", "LOCAL_DB", List.of(), false));
        mockMvc.perform(get("/api/admin/ai/observability/local/metrics").queryParam("from", FROM).queryParam("to", TO)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)).andExpect(status().isOk())
            .andExpect(jsonPath("$.source").value("LOCAL_DB"));
        when(service.metrics(FROM, TO, "bad")).thenThrow(new IllegalArgumentException("Invalid Job ID"));
        mockMvc.perform(get("/api/admin/ai/observability/local/metrics").queryParam("from", FROM).queryParam("to", TO).queryParam("jobId", "bad")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)).andExpect(status().isBadRequest());
        when(service.metrics(FROM, TO, null)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("private database error"));
        mockMvc.perform(get("/api/admin/ai/observability/local/metrics").queryParam("from", FROM).queryParam("to", TO)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)).andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.error.code").value("LOCAL_OBSERVABILITY_UNAVAILABLE"));
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

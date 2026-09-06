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
import org.urizo.axmodulestudio.backend.integration.ai.observability.LangfuseObservabilityService;

@WebMvcTest(controllers = AdminLangfuseObservabilityController.class)
@ActiveProfiles("local-full")
@Import(SecurityConfig.class)
class AdminLangfuseObservabilityControllerTest {

    private static final UUID ACTOR_ID =
            UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final String TOKEN = "langfuse-observability-test-token";
    private static final String FROM = "2026-09-01T00:00:00Z";
    private static final String TO = "2026-09-02T00:00:00Z";

    @Autowired private MockMvc mockMvc;
    @MockitoBean private LangfuseObservabilityService service;
    @MockitoBean private AuthService authService;
    @MockitoBean(name = "authJwtSigningKey") private SecretKey authJwtSigningKey;
    @MockitoBean private JwtEncoder jwtEncoder;
    @MockitoBean(name = "accessJwtDecoder") private JwtDecoder accessJwtDecoder;
    @MockitoBean(name = "refreshJwtDecoder") private JwtDecoder refreshJwtDecoder;
    @MockitoBean private JwtTokenProvider jwtTokenProvider;
    @MockitoBean private JwtProperties jwtProperties;

    @Test
    void superAdminCanReadTheThreeFixedViews() throws Exception {
        authenticate(AdminRole.SUPER_ADMIN);
        Instant from = Instant.parse(FROM);
        Instant to = Instant.parse(TO);
        when(service.metrics(FROM, TO)).thenReturn(
                new LangfuseObservabilityService.MetricsResponse(
                        LangfuseObservabilityService.Availability.AVAILABLE,
                        null, from, to, "local", List.of()));
        when(service.observations(FROM, TO)).thenReturn(
                new LangfuseObservabilityService.ObservationsResponse(
                        LangfuseObservabilityService.Availability.AVAILABLE,
                        null, from, to, "local", List.of()));
        when(service.scores(FROM, TO)).thenReturn(
                new LangfuseObservabilityService.ScoresResponse(
                        LangfuseObservabilityService.Availability.AVAILABLE,
                        null, from, to, "local", List.of()));

        for (String endpoint : List.of("metrics", "observations", "scores")) {
            mockMvc.perform(get("/api/admin/ai/observability/" + endpoint)
                            .queryParam("from", FROM)
                            .queryParam("to", TO)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("AVAILABLE"))
                    .andExpect(jsonPath("$.environment").value("local"));
        }
    }

    @Test
    void generalAdminIsForbiddenBeforeTheLangfuseService() throws Exception {
        authenticate(AdminRole.GENERAL_ADMIN);

        mockMvc.perform(get("/api/admin/ai/observability/metrics")
                        .queryParam("from", FROM)
                        .queryParam("to", TO)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        verifyNoInteractions(service);
    }

    @Test
    void invalidRangeHasAStableSafeError() throws Exception {
        authenticate(AdminRole.SUPER_ADMIN);
        when(service.metrics(FROM, TO)).thenThrow(
                new IllegalArgumentException("The observability range is invalid."));

        mockMvc.perform(get("/api/admin/ai/observability/metrics")
                        .queryParam("from", FROM)
                        .queryParam("to", TO)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_OBSERVABILITY_RANGE"));
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

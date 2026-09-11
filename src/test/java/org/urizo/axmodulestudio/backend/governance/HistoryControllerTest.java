package org.urizo.axmodulestudio.backend.governance;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
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

@WebMvcTest(controllers = HistoryController.class)
@ActiveProfiles("local-full")
@Import(SecurityConfig.class)
class HistoryControllerTest {
    static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final String TOKEN = "history-test-token";
    @Autowired MockMvc mvc;
    @MockitoBean HistoryService service;
    @MockitoBean AuthService authService;
    @MockitoBean(name = "authJwtSigningKey") SecretKey key;
    @MockitoBean JwtEncoder encoder;
    @MockitoBean(name = "accessJwtDecoder") JwtDecoder decoder;
    @MockitoBean(name = "refreshJwtDecoder") JwtDecoder refreshDecoder;
    @MockitoBean JwtTokenProvider tokenProvider;
    @MockitoBean JwtProperties properties;

    @Test
    void bothAdministratorRolesCanReadWithoutCaching() throws Exception {
        when(service.approvals(HistoryContract.Domain.RAG, "", null, 25))
                .thenReturn(new HistoryContract.Page(List.of(), null, Instant.EPOCH));
        for (AdminRole role : List.of(AdminRole.GENERAL_ADMIN, AdminRole.SUPER_ADMIN)) {
            authenticate(role);
            mvc.perform(get("/api/admin/governance/approvals").header("Authorization", "Bearer " + TOKEN))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.items").isEmpty()).andExpect(jsonPath("$.observedAt").exists());
        }
    }

    @Test
    void authenticationAndGeneralUserAreBlockedBeforeReads() throws Exception {
        mvc.perform(get("/api/admin/governance/runs")).andExpect(status().isUnauthorized());
        authenticate(AdminRole.GENERAL_USER);
        mvc.perform(get("/api/admin/governance/runs").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void invalidDomainAndServiceValidationReturn400() throws Exception {
        authenticate(AdminRole.GENERAL_ADMIN);
        mvc.perform(get("/api/admin/governance/approvals?domain=CMS").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("HISTORY_QUERY_INVALID"));
        when(service.runs(any(), anyString(), any(), anyInt())).thenThrow(new IllegalArgumentException("invalid cursor"));
        mvc.perform(get("/api/admin/governance/runs?cursor=bad").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isBadRequest());
    }

    @Test
    void failedDatabaseReadReturns503WithoutSqlLeak() throws Exception {
        authenticate(AdminRole.SUPER_ADMIN);
        when(service.runs(any(), anyString(), any(), anyInt()))
                .thenThrow(new DataAccessResourceFailureException("SELECT private_payload FROM private_table"));
        mvc.perform(get("/api/admin/governance/runs").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.error.code").value("HISTORY_UNAVAILABLE"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private_table"))));
    }

    @Test
    void governanceHasNoDecisionMutationEndpoint() throws Exception {
        authenticate(AdminRole.SUPER_ADMIN);
        mvc.perform(post("/api/admin/governance/approvals").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isMethodNotAllowed());
        verifyNoInteractions(service);
    }

    private void authenticate(AdminRole role) {
        when(decoder.decode(TOKEN)).thenReturn(Jwt.withTokenValue(TOKEN).header("alg", "HS256")
                .subject(ID.toString()).claim("token_type", "access").build());
        when(authService.loadActor(ID)).thenReturn(new AuthenticatedActor(ID, "관리자", role));
    }
}

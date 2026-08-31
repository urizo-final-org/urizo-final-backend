package org.urizo.axmodulestudio.backend.integration.ai.local;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
import org.urizo.axmodulestudio.backend.integration.ai.gateway.ModelProvider;

@WebMvcTest(controllers = LocalProviderCredentialController.class)
@ActiveProfiles({"local-full", "dev"})
@Import(SecurityConfig.class)
class LocalProviderCredentialControllerTest {

    private static final String ACCESS_TOKEN = "provider-credential-test-token";
    private static final String CSRF_TOKEN = "csrf-fixture";
    private static final UUID ACTOR_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LocalProviderSecretService secretService;

    @MockitoBean
    private LocalDevRequestGuard requestGuard;

    @MockitoBean
    private ProviderConnectionTestService connectionTestService;

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
    void superAdminCanManageCredentialsWithoutReceivingTheSecret() throws Exception {
        authenticate(AdminRole.SUPER_ADMIN);
        ProviderCredentialStatus missing = ProviderCredentialStatus.notConfigured(ModelProvider.OPENAI);
        ProviderCredentialStatus stored = new ProviderCredentialStatus(
                ModelProvider.OPENAI,
                true,
                ProviderCredentialState.STORED,
                "abc123fixture",
                Instant.parse("2026-08-31T00:01:00Z"),
                null);
        ProviderConnectionTestResult tested = new ProviderConnectionTestResult(
                ModelProvider.OPENAI,
                "fixture-model",
                ProviderCredentialState.VERIFIED,
                true,
                1,
                1,
                12,
                Instant.parse("2026-08-31T00:02:00Z"),
                "OK");
        when(requestGuard.csrfToken(any())).thenReturn(CSRF_TOKEN);
        when(secretService.statuses()).thenReturn(List.of(missing));
        when(secretService.store(ModelProvider.OPENAI, "fixture-credential-value")).thenReturn(stored);
        when(connectionTestService.test(ModelProvider.OPENAI)).thenReturn(tested);
        when(secretService.delete(ModelProvider.OPENAI)).thenReturn(missing);

        mockMvc.perform(get("/internal/dev/provider-credentials")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.csrfToken").value(CSRF_TOKEN))
                .andExpect(jsonPath("$.providers[0].configured").value(false))
                .andExpect(jsonPath("$.providers[0].credential").doesNotExist());

        mockMvc.perform(put("/internal/dev/provider-credentials/OPENAI")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
                        .header("Origin", "http://127.0.0.1:3000")
                        .header("X-AXMS-CSRF", CSRF_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"credential\":\"fixture-credential-value\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.fingerprintSuffix").value("abc123fixture"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("fixture-credential-value"))));

        mockMvc.perform(post("/internal/dev/provider-credentials/OPENAI/test")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
                        .header("Origin", "http://127.0.0.1:3000")
                        .header("X-AXMS-CSRF", CSRF_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("VERIFIED"))
                .andExpect(jsonPath("$.safeCode").value("OK"));

        mockMvc.perform(delete("/internal/dev/provider-credentials/OPENAI")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
                        .header("Origin", "http://127.0.0.1:3000")
                        .header("X-AXMS-CSRF", CSRF_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(false))
                .andExpect(jsonPath("$.credential").doesNotExist());
    }

    @Test
    void generalAdminIsForbiddenBeforeCredentialServices() throws Exception {
        authenticate(AdminRole.GENERAL_ADMIN);

        mockMvc.perform(get("/internal/dev/provider-credentials")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        mockMvc.perform(delete("/internal/dev/provider-credentials/OPENAI")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        verifyNoInteractions(secretService, requestGuard, connectionTestService);
    }

    private void authenticate(AdminRole role) {
        Jwt jwt = Jwt.withTokenValue(ACCESS_TOKEN)
                .header("alg", "HS256")
                .subject(ACTOR_ID.toString())
                .claim("token_type", "access")
                .build();
        when(accessJwtDecoder.decode(ACCESS_TOKEN)).thenReturn(jwt);
        when(authService.loadActor(ACTOR_ID)).thenReturn(new AuthenticatedActor(ACTOR_ID, "관리자", role));
    }
}

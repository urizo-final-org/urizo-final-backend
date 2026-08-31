package org.urizo.axmodulestudio.backend.cms.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import org.urizo.axmodulestudio.backend.cms.dto.CmsResponses.SiteSettingsView;
import org.urizo.axmodulestudio.backend.cms.dto.CmsResponses.SiteView;
import org.urizo.axmodulestudio.backend.cms.service.CmsSiteSettingsService;

@WebMvcTest(controllers = CmsSiteSettingsController.class)
@ActiveProfiles("local-full")
@Import(SecurityConfig.class)
class CmsSiteSettingsControllerTest {

    private static final UUID ACTOR_ID =
            UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID TRACE_ID =
            UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final String ACCESS_TOKEN = "cms-site-settings-test-token";
    private static final Instant UPDATED_AT = Instant.parse("2026-08-31T02:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CmsSiteSettingsService service;

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
    void superAdminCanReadAndSaveCmsSiteSettings() throws Exception {
        authenticate(AdminRole.SUPER_ADMIN);
        when(service.sites()).thenReturn(List.of(new SiteView(
                "main", "AX Studio", "/", "CLASSIC", true, true, UPDATED_AT)));
        when(service.saveSettings("main", "MINIMAL"))
                .thenReturn(new SiteSettingsView("main", "MINIMAL", UPDATED_AT));

        mockMvc.perform(get("/api/admin/cms/sites")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
                        .header("X-Trace-Id", TRACE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].key").value("main"))
                .andExpect(jsonPath("$[0].defaultSite").value(true));

        mockMvc.perform(put("/api/admin/cms/settings")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
                        .header("X-Trace-Id", TRACE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"defaultSiteKey":"main","defaultTemplateKey":"MINIMAL"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultSiteKey").value("main"))
                .andExpect(jsonPath("$.defaultTemplateKey").value("MINIMAL"));
    }

    @Test
    void generalAdminIsForbiddenBeforeTheSiteSettingsService() throws Exception {
        authenticate(AdminRole.GENERAL_ADMIN);

        mockMvc.perform(get("/api/admin/cms/sites")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
                        .header("X-Trace-Id", TRACE_ID))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        verifyNoInteractions(service);
    }

    @Test
    void superAdminCanCreateANonDefaultSite() throws Exception {
        authenticate(AdminRole.SUPER_ADMIN);
        when(service.createSite("campaign", "캠페인", "/campaign", "BOLD", true))
                .thenReturn(new SiteView(
                        "campaign", "캠페인", "/campaign", "BOLD",
                        true, false, UPDATED_AT));

        mockMvc.perform(post("/api/admin/cms/sites")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
                        .header("X-Trace-Id", TRACE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"key":"campaign","siteName":"캠페인",
                                 "publicPath":"/campaign","templateKey":"BOLD","enabled":true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("campaign"))
                .andExpect(jsonPath("$.defaultSite").value(false));
    }

    @Test
    void generalAdminCannotCreateASite() throws Exception {
        authenticate(AdminRole.GENERAL_ADMIN);

        mockMvc.perform(post("/api/admin/cms/sites")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
                        .header("X-Trace-Id", TRACE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"key":"campaign","siteName":"캠페인",
                                 "publicPath":"/campaign","templateKey":"BOLD","enabled":true}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        verifyNoInteractions(service);
    }

    @Test
    void missingAuthenticationIsRejectedBeforeTheSiteSettingsService() throws Exception {
        mockMvc.perform(get("/api/admin/cms/settings")
                        .header("X-Trace-Id", TRACE_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));
        verifyNoInteractions(service);
    }

    @Test
    void invalidPublicPathIsRejectedWithoutSaving() throws Exception {
        authenticate(AdminRole.SUPER_ADMIN);

        mockMvc.perform(put("/api/admin/cms/sites/main")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
                        .header("X-Trace-Id", TRACE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"siteName":"AX Studio","publicPath":"https://example.test",
                                 "templateKey":"CLASSIC","enabled":true}
                                """))
                .andExpect(status().isBadRequest());
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

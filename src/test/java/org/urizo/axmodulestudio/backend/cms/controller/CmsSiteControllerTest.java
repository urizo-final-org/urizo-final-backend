package org.urizo.axmodulestudio.backend.cms.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.urizo.axmodulestudio.backend.auth.config.JwtProperties;
import org.urizo.axmodulestudio.backend.cms.dto.CmsResponses.PublicSiteView;
import org.urizo.axmodulestudio.backend.cms.dto.CmsResponses.TemplateView;
import org.urizo.axmodulestudio.backend.cms.service.CmsService;
import org.urizo.axmodulestudio.backend.cms.service.CmsSiteSettingsService;

@WebMvcTest(controllers = CmsSiteController.class)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("local-full")
class CmsSiteControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CmsService cms;

    @MockitoBean
    private CmsSiteSettingsService siteSettings;

    @MockitoBean
    private JwtProperties jwtProperties;

    @Test
    void publicContextIsTheSingleResponseForSiteAndTemplatePresentation() throws Exception {
        Instant updatedAt = Instant.parse("2026-08-31T02:00:00Z");
        when(siteSettings.resolveSite("/campaign/news")).thenReturn(new PublicSiteView(
                "campaign", "캠페인", "/campaign",
                new TemplateView(
                        "BOLD", "BOLD", "#112233", "캠페인", "header", "footer",
                        "/hero.svg", "hero", "subtitle", "button", "/about",
                        false, updatedAt)));

        mockMvc.perform(get("/api/site/context").queryParam("path", "/campaign/news"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("campaign"))
                .andExpect(jsonPath("$.publicPath").value("/campaign"))
                .andExpect(jsonPath("$.template.key").value("BOLD"))
                .andExpect(jsonPath("$.template.siteName").value("캠페인"));
    }
}

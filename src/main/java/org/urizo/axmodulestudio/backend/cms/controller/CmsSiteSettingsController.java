package org.urizo.axmodulestudio.backend.cms.controller;

import java.util.List;

import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.urizo.axmodulestudio.backend.cms.dto.CmsRequests.SiteCreateRequest;
import org.urizo.axmodulestudio.backend.cms.dto.CmsRequests.SiteRequest;
import org.urizo.axmodulestudio.backend.cms.dto.CmsRequests.SiteSettingsRequest;
import org.urizo.axmodulestudio.backend.cms.dto.CmsResponses.SiteSettingsView;
import org.urizo.axmodulestudio.backend.cms.dto.CmsResponses.SiteView;
import org.urizo.axmodulestudio.backend.cms.service.CmsSiteSettingsService;

@RestController
@Validated
@Profile("local-full")
@RequestMapping("/api/admin/cms")
public class CmsSiteSettingsController {

    private final CmsSiteSettingsService settings;

    public CmsSiteSettingsController(CmsSiteSettingsService settings) {
        this.settings = settings;
    }

    @GetMapping("/settings")
    SiteSettingsView settings() {
        return settings.settings();
    }

    @PutMapping("/settings")
    SiteSettingsView saveSettings(@Valid @RequestBody SiteSettingsRequest request) {
        return settings.saveSettings(request.defaultSiteKey(), request.defaultTemplateKey());
    }

    @GetMapping("/sites")
    List<SiteView> sites() {
        return settings.sites();
    }

    @PostMapping("/sites")
    SiteView createSite(@Valid @RequestBody SiteCreateRequest request) {
        return settings.createSite(request.key(), request.siteName(), request.publicPath(),
                request.templateKey(), request.enabled());
    }

    @PutMapping("/sites/{key}")
    SiteView updateSite(@PathVariable String key, @Valid @RequestBody SiteRequest request) {
        return settings.updateSite(key, request.siteName(), request.publicPath(),
                request.templateKey(), request.enabled());
    }
}

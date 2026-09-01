package org.urizo.axmodulestudio.backend.cms.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class CmsRequests {

    private CmsRequests() {
    }

    public record MenuRequest(
            @NotBlank @Size(max = 80) String name,
            @NotBlank @Size(max = 180) String path,
            Long parentId,
            @Min(0) int displayOrder,
            @NotBlank @Pattern(regexp = "NONE|CONTENT|BOARD") String targetType,
            Long targetId) {
    }

    public record ArticleRequest(
            @NotBlank @Size(max = 200) String title,
            @NotBlank @Size(max = 20000) String body) {
    }

    public record BoardRequest(
            @NotBlank @Size(max = 100) String name,
            @Size(max = 300) String description) {
    }

    public record TemplateRequest(
            @NotBlank @Size(max = 40) String layout,
            @NotBlank @Pattern(regexp = "^#[0-9A-Fa-f]{6}$") String primaryColor,
            @NotBlank @Size(max = 100) String siteName,
            @Size(max = 200) String headerText,
            @Size(max = 200) String footerText,
            @NotBlank @Size(max = 500) String heroImageUrl,
            @NotBlank @Size(max = 160) String heroTitle,
            @Size(max = 300) String heroSubtitle,
            @Size(max = 60) String heroButtonLabel,
            @Size(max = 180) String heroButtonUrl) {
    }

    public record SiteSettingsRequest(
            @NotBlank @Size(max = 40) String defaultSiteKey,
            @NotBlank @Size(max = 40) String defaultTemplateKey) {
    }

    public record SiteCreateRequest(
            @NotBlank @Size(max = 40)
            @Pattern(regexp = "^[A-Za-z0-9_-]+$") String key,
            @NotBlank @Size(max = 100) String siteName,
            @NotBlank @Size(max = 180)
            @Pattern(regexp = "^/(?:[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*)?$")
            String publicPath,
            @NotBlank @Size(max = 40) String templateKey,
            boolean enabled) {
    }

    public record SiteRequest(
            @NotBlank @Size(max = 100) String siteName,
            @NotBlank @Size(max = 180)
            @Pattern(regexp = "^/(?:[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*)?$")
            String publicPath,
            @NotBlank @Size(max = 40) String templateKey,
            boolean enabled) {
    }
}

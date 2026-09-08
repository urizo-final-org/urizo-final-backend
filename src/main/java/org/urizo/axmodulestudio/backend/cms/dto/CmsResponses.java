package org.urizo.axmodulestudio.backend.cms.dto;

import java.time.Instant;
import java.util.UUID;

public final class CmsResponses {

    private CmsResponses() {
    }

    public record MemberView(UUID id, String loginId, String name, String role) {
    }

    public record MenuView(
            long id,
            String name,
            String path,
            Long parentId,
            int displayOrder,
            String targetType,
            Long targetId) {
    }

    public record ContentView(
            long id,
            UUID authorId,
            String authorName,
            String title,
            String body,
            Instant createdAt,
            Instant updatedAt) {
    }

    /**
     * 업로드 응답. 화면은 {@code id}로 본문에 넣을 주소를 만든다.
     *
     * <p>바이트는 담지 않는다. 이 기록은 JSON으로 나가고 바이트는 조회 경로가 따로 내보낸다.
     */
    public record ContentImageView(long id, String contentType, int byteSize) {
    }

    /** 조회 경로가 그대로 내보내는 바이트. JSON으로 직렬화하지 않는다. */
    public record ContentImageBytes(String contentType, byte[] bytes) {
    }

    public record BoardView(
            long id,
            String name,
            String description,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record PostView(
            long id,
            long boardId,
            UUID authorId,
            String authorName,
            String title,
            String body,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record TemplateView(
            String key,
            String layout,
            String primaryColor,
            String siteName,
            String headerText,
            String footerText,
            String heroImageUrl,
            String heroTitle,
            String heroSubtitle,
            String heroButtonLabel,
            String heroButtonUrl,
            boolean active,
            Instant updatedAt) {
    }

    public record SiteSettingsView(
            String defaultSiteKey,
            String defaultTemplateKey,
            Instant updatedAt) {
    }

    public record SiteView(
            String key,
            String name,
            String publicPath,
            String templateKey,
            boolean enabled,
            boolean defaultSite,
            Instant updatedAt) {
    }

    public record PublicSiteView(
            String key,
            String name,
            String publicPath,
            TemplateView template) {
    }
}

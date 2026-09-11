package org.urizo.axmodulestudio.backend.cms.dto;

import java.time.Instant;
import java.util.List;
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
            Instant updatedAt,
            String displayType,
            String regionGroupKey,
            String categoryGroupKey) {
        public BoardView(long id, String name, String description, Instant createdAt, Instant updatedAt) {
            this(id, name, description, createdAt, updatedAt, "LIST", null, null);
        }
    }

    public record PostView(
            long id,
            long boardId,
            UUID authorId,
            String authorName,
            String title,
            String body,
            Instant createdAt,
            Instant updatedAt,
            Long thumbnailImageId,
            String thumbnailAlt,
            Long regionCodeId,
            Long categoryCodeId) {
        public PostView(long id, long boardId, UUID authorId, String authorName, String title,
                        String body, Instant createdAt, Instant updatedAt) {
            this(id, boardId, authorId, authorName, title, body, createdAt, updatedAt, null, "", null, null);
        }
    }

    public record CodeGroupView(String key, String label, int displayOrder, boolean enabled) {
    }

    public record CodeView(long id, String groupKey, String value, String label, int displayOrder, boolean enabled) {
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
            Instant updatedAt,
            List<String> heroImageUrls, List<TemplateHeroImage> heroImages) {
        public TemplateView {
            heroImageUrls = heroImageUrls == null
                    ? (heroImageUrl == null || heroImageUrl.isBlank() ? List.of() : List.of(heroImageUrl))
                    : List.copyOf(heroImageUrls);
            heroImages = heroImages == null
                    ? heroImageUrls.stream().map(url -> new TemplateHeroImage(url, "", "")).toList()
                    : List.copyOf(heroImages);
            heroImageUrls = heroImages.stream().map(TemplateHeroImage::url).toList();
            heroImageUrl = heroImageUrls.isEmpty() ? "" : heroImageUrls.get(0);
        }

        public TemplateView(String key, String layout, String primaryColor, String siteName,
                String headerText, String footerText, String heroImageUrl, String heroTitle,
                String heroSubtitle, String heroButtonLabel, String heroButtonUrl,
                boolean active, Instant updatedAt, List<String> heroImageUrls) {
            this(key, layout, primaryColor, siteName, headerText, footerText, heroImageUrl,
                    heroTitle, heroSubtitle, heroButtonLabel, heroButtonUrl, active, updatedAt, heroImageUrls, null);
        }

        public TemplateView(String key, String layout, String primaryColor, String siteName,
                String headerText, String footerText, String heroImageUrl, String heroTitle,
                String heroSubtitle, String heroButtonLabel, String heroButtonUrl,
                boolean active, Instant updatedAt) {
            this(key, layout, primaryColor, siteName, headerText, footerText, heroImageUrl,
                    heroTitle, heroSubtitle, heroButtonLabel, heroButtonUrl, active, updatedAt, null);
        }
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

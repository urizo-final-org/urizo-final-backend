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
}

package org.urizo.axmodulestudio.backend.cms.entity;

import org.urizo.axmodulestudio.backend.cms.dto.TemplateHeroImage;

import java.time.Instant;
import java.util.List;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "cms_template", schema = "app")
public class CmsTemplateEntity {

    @Id
    @Column(name = "template_key", nullable = false, updatable = false, length = 40)
    private String templateKey;

    @Column(name = "layout", nullable = false, length = 40)
    private String layout;

    @Column(name = "primary_color", nullable = false, length = 16)
    private String primaryColor;

    @Column(name = "site_name", nullable = false, length = 100)
    private String siteName;

    @Column(name = "header_text", nullable = false, length = 200)
    private String headerText;

    @Column(name = "footer_text", nullable = false, length = 200)
    private String footerText;

    @Column(name = "hero_image_url", nullable = false, length = 500)
    private String heroImageUrl;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "hero_image_urls", columnDefinition = "jsonb")
    private List<String> heroImageUrls;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "hero_images", columnDefinition = "jsonb")
    private List<TemplateHeroImage> heroImages;

    public List<TemplateHeroImage> getHeroImages() {
        return heroImages == null
                ? getHeroImageUrls().stream().map(url -> new TemplateHeroImage(url, "", "")).toList()
                : List.copyOf(heroImages);
    }

    @Column(name = "hero_title", nullable = false, length = 160)
    private String heroTitle;

    @Column(name = "hero_subtitle", nullable = false, length = 300)
    private String heroSubtitle;

    @Column(name = "hero_button_label", nullable = false, length = 60)
    private String heroButtonLabel;

    @Column(name = "hero_button_url", nullable = false, length = 180)
    private String heroButtonUrl;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "active_yn", nullable = false, length = 1, columnDefinition = "char(1)")
    private String activeYn;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CmsTemplateEntity() {
    }

    public String getTemplateKey() {
        return templateKey;
    }

    public String getLayout() {
        return layout;
    }

    public String getPrimaryColor() {
        return primaryColor;
    }

    public String getSiteName() {
        return siteName;
    }

    public String getHeaderText() {
        return headerText;
    }

    public String getFooterText() {
        return footerText;
    }

    public String getHeroImageUrl() {
        return heroImageUrl;
    }

    public List<String> getHeroImageUrls() {
        return heroImageUrls == null
                ? (heroImageUrl == null || heroImageUrl.isBlank() ? List.of() : List.of(heroImageUrl))
                : List.copyOf(heroImageUrls);
    }

    public String getHeroTitle() {
        return heroTitle;
    }

    public String getHeroSubtitle() {
        return heroSubtitle;
    }

    public String getHeroButtonLabel() {
        return heroButtonLabel;
    }

    public String getHeroButtonUrl() {
        return heroButtonUrl;
    }

    public boolean isActive() {
        return "Y".equals(activeYn);
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void changePresentation(
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
            List<TemplateHeroImage> heroImages,
            Instant changedAt) {
        this.layout = layout;
        this.primaryColor = primaryColor;
        this.siteName = siteName;
        this.headerText = headerText;
        this.footerText = footerText;
        this.heroImageUrl = heroImageUrl;
        this.heroImages = List.copyOf(heroImages);
        this.heroImageUrls = heroImages.stream().map(TemplateHeroImage::url).toList();
        this.heroTitle = heroTitle;
        this.heroSubtitle = heroSubtitle;
        this.heroButtonLabel = heroButtonLabel;
        this.heroButtonUrl = heroButtonUrl;
        this.updatedAt = changedAt;
    }
}

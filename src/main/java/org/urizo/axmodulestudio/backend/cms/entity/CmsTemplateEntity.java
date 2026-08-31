package org.urizo.axmodulestudio.backend.cms.entity;

import java.time.Instant;

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

    public void deactivate() {
        this.activeYn = "N";
    }

    public void markActive(Instant changedAt) {
        this.activeYn = "Y";
        this.updatedAt = changedAt;
    }

    public void activate(
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
            Instant changedAt) {
        this.layout = layout;
        this.primaryColor = primaryColor;
        this.siteName = siteName;
        this.headerText = headerText;
        this.footerText = footerText;
        this.heroImageUrl = heroImageUrl;
        this.heroTitle = heroTitle;
        this.heroSubtitle = heroSubtitle;
        this.heroButtonLabel = heroButtonLabel;
        this.heroButtonUrl = heroButtonUrl;
        this.activeYn = "Y";
        this.updatedAt = changedAt;
    }
}

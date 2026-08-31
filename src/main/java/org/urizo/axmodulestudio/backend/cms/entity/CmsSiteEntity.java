package org.urizo.axmodulestudio.backend.cms.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "cms_site", schema = "app")
public class CmsSiteEntity {

    @Id
    @Column(name = "site_key", nullable = false, updatable = false, length = 40)
    private String siteKey;

    @Column(name = "site_name", nullable = false, length = 100)
    private String siteName;

    @Column(name = "public_path", nullable = false, length = 180)
    private String publicPath;

    @Column(name = "template_key", nullable = false, length = 40)
    private String templateKey;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "enabled_yn", nullable = false, length = 1, columnDefinition = "char(1)")
    private String enabledYn;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "default_yn", nullable = false, length = 1, columnDefinition = "char(1)")
    private String defaultYn;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CmsSiteEntity() {
    }

    public String getSiteKey() {
        return siteKey;
    }

    public String getSiteName() {
        return siteName;
    }

    public String getPublicPath() {
        return publicPath;
    }

    public String getTemplateKey() {
        return templateKey;
    }

    public boolean isEnabled() {
        return "Y".equals(enabledYn);
    }

    public boolean isDefault() {
        return "Y".equals(defaultYn);
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void change(
            String siteName, String publicPath, String templateKey,
            boolean enabled, Instant changedAt) {
        this.siteName = siteName;
        this.publicPath = publicPath;
        this.templateKey = templateKey;
        this.enabledYn = enabled ? "Y" : "N";
        this.updatedAt = changedAt;
    }

    public void clearDefault() {
        this.defaultYn = "N";
    }

    public void selectAsDefault(String templateKey, Instant changedAt) {
        this.templateKey = templateKey;
        this.defaultYn = "Y";
        this.updatedAt = changedAt;
    }

    public void applyTemplate(String templateKey, Instant changedAt) {
        this.templateKey = templateKey;
        this.updatedAt = changedAt;
    }
}

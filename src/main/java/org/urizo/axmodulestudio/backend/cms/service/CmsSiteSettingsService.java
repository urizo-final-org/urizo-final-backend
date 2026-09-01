package org.urizo.axmodulestudio.backend.cms.service;

import static org.urizo.axmodulestudio.backend.cms.service.CmsServiceException.invalidRequest;
import static org.urizo.axmodulestudio.backend.cms.service.CmsServiceException.notFound;

import java.util.Comparator;
import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.urizo.axmodulestudio.backend.cms.dto.CmsResponses.SiteSettingsView;
import org.urizo.axmodulestudio.backend.cms.dto.CmsResponses.SiteView;
import org.urizo.axmodulestudio.backend.cms.dto.CmsResponses.PublicSiteView;
import org.urizo.axmodulestudio.backend.cms.dto.CmsResponses.TemplateView;
import org.urizo.axmodulestudio.backend.cms.repository.CmsRepository;
import org.urizo.axmodulestudio.backend.cms.repository.CmsSiteRepository;

@Service
@Profile("local-full")
public class CmsSiteSettingsService {

    private final CmsSiteRepository sites;
    private final CmsRepository cms;

    public CmsSiteSettingsService(CmsSiteRepository sites, CmsRepository cms) {
        this.sites = sites;
        this.cms = cms;
    }

    @Transactional(transactionManager = "authJpaTransactionManager", readOnly = true)
    public SiteSettingsView settings() {
        SiteView site = defaultSite();
        return new SiteSettingsView(site.key(), site.templateKey(), site.updatedAt());
    }

    @Transactional(transactionManager = "authJpaTransactionManager")
    public SiteSettingsView saveSettings(String defaultSiteKey, String defaultTemplateKey) {
        SiteView site = site(defaultSiteKey);
        if (!site.enabled()) {
            throw invalidRequest("사용 중인 사이트만 기본 사이트로 지정할 수 있습니다.");
        }
        template(defaultTemplateKey);
        sites.selectDefault(site.key(), defaultTemplateKey);
        return settings();
    }

    @Transactional(transactionManager = "authJpaTransactionManager", readOnly = true)
    public List<SiteView> sites() {
        return sites.findAll();
    }

    @Transactional(transactionManager = "authJpaTransactionManager")
    public SiteView createSite(
            String key, String siteName, String publicPath,
            String templateKey, boolean enabled) {
        String normalizedKey = normalizeSiteKey(key);
        requireText(siteName, "사이트명");
        String path = normalizePublicPath(publicPath);
        if (sites.findByKey(normalizedKey).isPresent()) {
            throw invalidRequest("이미 사용 중인 사이트 키입니다.");
        }
        if (sites.findByPublicPath(path).isPresent()) {
            throw invalidRequest("이미 사용 중인 공개 경로입니다.");
        }
        template(templateKey);
        return sites.create(
                normalizedKey, siteName.trim(), path, templateKey.trim(), enabled);
    }

    @Transactional(transactionManager = "authJpaTransactionManager")
    public SiteView updateSite(
            String key, String siteName, String publicPath, String templateKey, boolean enabled) {
        SiteView current = site(key);
        requireText(siteName, "사이트명");
        String path = normalizePublicPath(publicPath);
        template(templateKey);
        sites.findByPublicPath(path)
                .filter(existing -> !existing.key().equals(current.key()))
                .ifPresent(existing -> {
                    throw invalidRequest("이미 사용 중인 공개 경로입니다.");
                });
        if (current.defaultSite() && !enabled) {
            throw invalidRequest("기본 사이트는 사용 중지할 수 없습니다.");
        }
        sites.update(current.key(), siteName.trim(), path, templateKey, enabled);
        return site(current.key());
    }

    @Transactional(transactionManager = "authJpaTransactionManager", readOnly = true)
    public PublicSiteView resolveSite(String requestPath) {
        String path = normalizeRequestPath(requestPath);
        SiteView site = sites.findEnabled().stream()
                .filter(candidate -> matches(candidate.publicPath(), path))
                .max(Comparator.comparingInt(candidate -> candidate.publicPath().length()))
                .orElseGet(this::defaultSite);
        TemplateView template = template(site.templateKey());
        TemplateView presentation = new TemplateView(
                template.key(), template.layout(), template.primaryColor(),
                site.name(), template.headerText(), template.footerText(), template.heroImageUrl(),
                template.heroTitle(), template.heroSubtitle(), template.heroButtonLabel(),
                template.heroButtonUrl(), template.active(), template.updatedAt());
        return new PublicSiteView(site.key(), site.name(), site.publicPath(), presentation);
    }

    private SiteView defaultSite() {
        return sites.findDefault()
                .filter(SiteView::enabled)
                .orElseThrow(() -> notFound("사용 가능한 기본 사이트를 찾을 수 없습니다."));
    }

    private SiteView site(String key) {
        if (key == null || key.isBlank()) {
            throw invalidRequest("기본 사이트를 선택해야 합니다.");
        }
        return sites.findByKey(key.trim())
                .orElseThrow(() -> notFound("사이트를 찾을 수 없습니다."));
    }

    private TemplateView template(String key) {
        if (key == null || key.isBlank()) {
            throw invalidRequest("템플릿을 선택해야 합니다.");
        }
        return cms.findTemplate(key.trim())
                .orElseThrow(() -> notFound("템플릿을 찾을 수 없습니다."));
    }

    private static String normalizePublicPath(String value) {
        requireText(value, "공개 경로");
        String path = value.trim();
        if (!path.matches("^/(?:[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*)?$")) {
            throw invalidRequest("공개 경로는 / 또는 /로 시작하는 영문·숫자 경로여야 합니다.");
        }
        if (isReservedPublicPath(path)) {
            throw invalidRequest("관리자 및 내부 API 경로는 공개 경로로 사용할 수 없습니다.");
        }
        return path;
    }

    private static String normalizeSiteKey(String value) {
        requireText(value, "사이트 키");
        String key = value.trim();
        if (!key.matches("^[A-Za-z0-9_-]+$")) {
            throw invalidRequest("사이트 키는 영문·숫자·-·_만 사용할 수 있습니다.");
        }
        return key;
    }

    private static boolean isReservedPublicPath(String path) {
        return reservedPath(path, "/admin")
                || reservedPath(path, "/api")
                || reservedPath(path, "/internal")
                || reservedPath(path, "/actuator");
    }

    private static boolean reservedPath(String path, String reserved) {
        return path.equals(reserved) || path.startsWith(reserved + "/");
    }

    private static String normalizeRequestPath(String value) {
        if (value == null || value.isBlank() || !value.startsWith("/")) {
            return "/";
        }
        int query = value.indexOf('?');
        int fragment = value.indexOf('#');
        int end = query < 0 ? value.length() : query;
        if (fragment >= 0) {
            end = Math.min(end, fragment);
        }
        return value.substring(0, end);
    }

    private static boolean matches(String publicPath, String requestPath) {
        return "/".equals(publicPath)
                || requestPath.equals(publicPath)
                || requestPath.startsWith(publicPath + "/");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw invalidRequest(field + "은(는) 필수입니다.");
        }
    }
}

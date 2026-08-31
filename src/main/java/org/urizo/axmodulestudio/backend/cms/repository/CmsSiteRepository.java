package org.urizo.axmodulestudio.backend.cms.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.urizo.axmodulestudio.backend.cms.dto.CmsResponses.SiteView;
import org.urizo.axmodulestudio.backend.cms.entity.CmsSiteEntity;

@Repository
@Profile("local-full")
public class CmsSiteRepository {

    private static final String YES = "Y";

    private final CmsSiteJpaRepository repository;

    public CmsSiteRepository(CmsSiteJpaRepository repository) {
        this.repository = repository;
    }

    public List<SiteView> findAll() {
        return repository.findAllByOrderBySiteKeyAsc().stream().map(CmsSiteRepository::site).toList();
    }

    public List<SiteView> findEnabled() {
        return repository.findAllByEnabledYn(YES).stream().map(CmsSiteRepository::site).toList();
    }

    public Optional<SiteView> findByKey(String key) {
        return repository.findById(key).map(CmsSiteRepository::site);
    }

    public Optional<SiteView> findDefault() {
        return repository.findFirstByDefaultYn(YES).map(CmsSiteRepository::site);
    }

    public Optional<SiteView> findByPublicPath(String publicPath) {
        return repository.findFirstByPublicPath(publicPath).map(CmsSiteRepository::site);
    }

    public void update(
            String key, String siteName, String publicPath, String templateKey, boolean enabled) {
        CmsSiteEntity site = repository.findById(key).orElseThrow();
        site.change(siteName, publicPath, templateKey, enabled, Instant.now());
    }

    public void selectDefault(String key, String templateKey) {
        repository.findAllByDefaultYn(YES).forEach(CmsSiteEntity::clearDefault);
        repository.flush();
        repository.findById(key).orElseThrow().selectAsDefault(templateKey, Instant.now());
    }

    public void applyTemplateToDefault(String templateKey) {
        repository.findFirstByDefaultYn(YES)
                .ifPresent(site -> site.applyTemplate(templateKey, Instant.now()));
    }

    private static SiteView site(CmsSiteEntity site) {
        return new SiteView(site.getSiteKey(), site.getSiteName(), site.getPublicPath(),
                site.getTemplateKey(), site.isEnabled(), site.isDefault(), site.getUpdatedAt());
    }
}

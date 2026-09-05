package org.urizo.axmodulestudio.backend.cms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.urizo.axmodulestudio.backend.cms.dto.CmsResponses.SiteView;
import org.urizo.axmodulestudio.backend.cms.dto.CmsResponses.TemplateView;
import org.urizo.axmodulestudio.backend.cms.repository.CmsRepository;
import org.urizo.axmodulestudio.backend.cms.repository.CmsSiteRepository;

@ExtendWith(MockitoExtension.class)
class CmsSiteSettingsServiceTest {

    private static final Instant UPDATED_AT = Instant.parse("2026-08-31T02:00:00Z");

    @Mock
    private CmsSiteRepository sites;

    @Mock
    private CmsRepository cms;

    @InjectMocks
    private CmsSiteSettingsService service;

    @Test
    void savesEnabledDefaultSiteTemplateWithoutChangingGlobalTemplateAuthority() {
        SiteView main = site("main", "AX Studio", "/", "CLASSIC", true, false);
        SiteView saved = site("main", "AX Studio", "/", "MINIMAL", true, true);
        when(sites.findByKey("main")).thenReturn(Optional.of(main));
        when(cms.findTemplate("MINIMAL")).thenReturn(Optional.of(template("MINIMAL")));
        when(sites.findDefault()).thenReturn(Optional.of(saved));

        var result = service.saveSettings("main", "MINIMAL");

        assertThat(result.defaultSiteKey()).isEqualTo("main");
        assertThat(result.defaultTemplateKey()).isEqualTo("MINIMAL");
        verify(sites).selectDefault("main", "MINIMAL");
        verify(cms).findTemplate("MINIMAL");
        verifyNoMoreInteractions(cms);
    }

    @Test
    void rejectsDisabledDefaultSiteWithoutChangingSettings() {
        when(sites.findByKey("disabled"))
                .thenReturn(Optional.of(site("disabled", "중지", "/off", "CLASSIC", false, false)));

        assertThatThrownBy(() -> service.saveSettings("disabled", "CLASSIC"))
                .isInstanceOf(CmsServiceException.class)
                .hasMessage("사용 중인 사이트만 기본 사이트로 지정할 수 있습니다.");
        verify(sites, never()).selectDefault("disabled", "CLASSIC");
    }

    @Test
    void createsANonDefaultSiteWithItsOwnTemplateSelection() {
        SiteView created = site(
                "campaign", "캠페인", "/campaign", "BOLD", true, false);
        when(sites.findByKey("campaign")).thenReturn(Optional.empty());
        when(sites.findByPublicPath("/campaign")).thenReturn(Optional.empty());
        when(cms.findTemplate("BOLD")).thenReturn(Optional.of(template("BOLD")));
        when(sites.create("campaign", "캠페인", "/campaign", "BOLD", true))
                .thenReturn(created);

        assertThat(service.createSite(
                " campaign ", " 캠페인 ", "/campaign", "BOLD", true))
                .isEqualTo(created);
        verify(sites).create("campaign", "캠페인", "/campaign", "BOLD", true);
    }

    @Test
    void rejectsDuplicateSiteKeyWithAPreciseError() {
        when(sites.findByKey("campaign")).thenReturn(Optional.of(site(
                "campaign", "기존 캠페인", "/existing", "CLASSIC", true, false)));

        assertThatThrownBy(() -> service.createSite(
                "campaign", "새 캠페인", "/campaign", "BOLD", true))
                .isInstanceOf(CmsServiceException.class)
                .hasMessage("이미 사용 중인 사이트 키입니다.");
        verify(sites, never()).create(
                "campaign", "새 캠페인", "/campaign", "BOLD", true);
    }

    @Test
    void rejectsDuplicatePublicPathWithAPreciseError() {
        when(sites.findByKey("campaign")).thenReturn(Optional.empty());
        when(sites.findByPublicPath("/campaign")).thenReturn(Optional.of(site(
                "existing", "기존 캠페인", "/campaign", "CLASSIC", true, false)));

        assertThatThrownBy(() -> service.createSite(
                "campaign", "새 캠페인", "/campaign", "BOLD", true))
                .isInstanceOf(CmsServiceException.class)
                .hasMessage("이미 사용 중인 공개 경로입니다.");
        verify(sites, never()).create(
                "campaign", "새 캠페인", "/campaign", "BOLD", true);
    }

    @Test
    void updatesOnlyTheSelectedSiteAndRejectsDuplicatePaths() {
        SiteView current = site("campaign", "캠페인", "/campaign", "BOLD", true, false);
        SiteView updated = site("campaign", "새 캠페인", "/event", "MINIMAL", true, false);
        when(sites.findByKey("campaign")).thenReturn(Optional.of(current), Optional.of(updated));
        when(cms.findTemplate("MINIMAL")).thenReturn(Optional.of(template("MINIMAL")));
        when(sites.findByPublicPath("/event")).thenReturn(Optional.empty());

        assertThat(service.updateSite(
                "campaign", " 새 캠페인 ", "/event", "MINIMAL", true)).isEqualTo(updated);
        verify(sites).update("campaign", "새 캠페인", "/event", "MINIMAL", true);

        when(sites.findByKey("campaign")).thenReturn(Optional.of(current));
        when(sites.findByPublicPath("/event"))
                .thenReturn(Optional.of(site("main", "메인", "/event", "CLASSIC", true, true)));
        assertThatThrownBy(() -> service.updateSite(
                "campaign", "캠페인", "/event", "MINIMAL", true))
                .isInstanceOf(CmsServiceException.class)
                .hasMessage("이미 사용 중인 공개 경로입니다.");
    }

    @Test
    void keepsTheDefaultSiteEnabled() {
        when(sites.findByKey("main"))
                .thenReturn(Optional.of(site("main", "메인", "/", "CLASSIC", true, true)));
        when(cms.findTemplate("CLASSIC")).thenReturn(Optional.of(template("CLASSIC")));
        when(sites.findByPublicPath("/")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateSite(
                "main", "메인", "/", "CLASSIC", false))
                .isInstanceOf(CmsServiceException.class)
                .hasMessage("기본 사이트는 사용 중지할 수 없습니다.");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/admin", "/admin/users",
            "/api", "/api/site",
            "/internal", "/internal/jobs",
            "/actuator", "/actuator/health"
    })
    void rejectsReservedPublicPathsAndTheirDescendants(String reservedPath) {
        when(sites.findByKey("campaign"))
                .thenReturn(Optional.of(site(
                        "campaign", "캠페인", "/campaign", "CLASSIC", true, false)));

        assertThatThrownBy(() -> service.updateSite(
                "campaign", "캠페인", reservedPath, "CLASSIC", true))
                .isInstanceOf(CmsServiceException.class)
                .hasMessage("관리자 및 내부 API 경로는 공개 경로로 사용할 수 없습니다.");
        verify(sites, never()).update(
                "campaign", "캠페인", reservedPath, "CLASSIC", true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/administrator", "/apiary"})
    void allowsPublicPathsThatOnlyShareAReservedPrefix(String publicPath) {
        SiteView current = site(
                "campaign", "캠페인", "/campaign", "CLASSIC", true, false);
        SiteView updated = site(
                "campaign", "캠페인", publicPath, "CLASSIC", true, false);
        when(sites.findByKey("campaign"))
                .thenReturn(Optional.of(current), Optional.of(updated));
        when(cms.findTemplate("CLASSIC")).thenReturn(Optional.of(template("CLASSIC")));
        when(sites.findByPublicPath(publicPath)).thenReturn(Optional.empty());

        assertThat(service.updateSite(
                "campaign", "캠페인", publicPath, "CLASSIC", true)).isEqualTo(updated);
        verify(sites).update("campaign", "캠페인", publicPath, "CLASSIC", true);
    }

    @Test
    void resolvesTheLongestEnabledSitePathAndOverlaysOnlyItsName() {
        SiteView main = site("main", "메인", "/", "CLASSIC", true, true);
        SiteView campaign = site("campaign", "여름 캠페인", "/campaign", "BOLD", true, false);
        when(sites.findEnabled()).thenReturn(List.of(main, campaign));
        when(cms.findTemplate("BOLD")).thenReturn(Optional.of(template("BOLD")));

        var result = service.resolveSite("/campaign/news?preview=true");

        assertThat(result.key()).isEqualTo("campaign");
        assertThat(result.publicPath()).isEqualTo("/campaign");
        assertThat(result.template().key()).isEqualTo("BOLD");
        assertThat(result.template().siteName()).isEqualTo("여름 캠페인");
        assertThat(result.template().primaryColor()).isEqualTo("#112233");
    }

    private static SiteView site(
            String key, String name, String path, String templateKey,
            boolean enabled, boolean defaultSite) {
        return new SiteView(key, name, path, templateKey, enabled, defaultSite, UPDATED_AT);
    }

    private static TemplateView template(String key) {
        return new TemplateView(key, key, "#112233", "템플릿 예시명", "header", "footer",
                "/hero.svg", "hero", "subtitle", "button", "/about", false, UPDATED_AT);
    }
}

package org.urizo.axmodulestudio.backend.cms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.urizo.axmodulestudio.backend.cms.dto.CmsResponses.ContentImageView;
import org.urizo.axmodulestudio.backend.cms.dto.CmsResponses.ContentView;
import org.urizo.axmodulestudio.backend.cms.dto.CmsResponses.MenuView;
import org.urizo.axmodulestudio.backend.cms.dto.CmsResponses.TemplateView;
import org.urizo.axmodulestudio.backend.cms.repository.CmsRepository;

@ExtendWith(MockitoExtension.class)
class CmsServiceTest {

    @Mock
    private CmsRepository repository;

    @Mock
    private CmsSiteSettingsService siteSettings;

    @InjectMocks
    private CmsService service;

    @Test
    void createsMenuAfterValidatingParentAndMappedContent() {
        MenuView parent = new MenuView(10L, "소개", "/about", null, 10, "NONE", null);
        ContentView content = new ContentView(
                20L, UUID.randomUUID(), "관리자", "회사 소개", "본문", Instant.now(), Instant.now());
        MenuView created = new MenuView(
                30L, "회사 소개", "/about/company", 10L, 11, "CONTENT", 20L);
        when(repository.findMenu(10L)).thenReturn(Optional.of(parent));
        when(repository.findContent(20L)).thenReturn(Optional.of(content));
        when(repository.insertMenu("회사 소개", "/about/company", 10L, 11, "CONTENT", 20L))
                .thenReturn(30L);
        when(repository.findMenu(30L)).thenReturn(Optional.of(created));

        MenuView result = service.createMenu(
                " 회사 소개 ", "/about/company", 10L, 11, "CONTENT", 20L);

        assertThat(result).isEqualTo(created);
        verify(repository).insertMenu(
                "회사 소개", "/about/company", 10L, 11, "CONTENT", 20L);
    }

    @Test
    void removesContentMappingInTheSameServiceOperation() {
        when(repository.softDeleteContent(42L)).thenReturn(1);

        service.deleteContent(42L);

        verify(repository).clearMenuTarget("CONTENT", 42L);
    }

    @Test
    void rejectsMissingContentWithoutRunningFollowUpUpdates() {
        when(repository.softDeleteContent(42L)).thenReturn(0);

        assertThatThrownBy(() -> service.deleteContent(42L))
                .isInstanceOf(CmsServiceException.class)
                .extracting(failure -> ((CmsServiceException) failure).kind())
                .isEqualTo(CmsServiceException.Kind.NOT_FOUND);
    }

    @Test
    void savesTemplatePresentationWithoutChangingSiteOrGlobalSelection() {
        TemplateView saved = new TemplateView(
                "MINIMAL", "MINIMAL", "#0E9F76", "AX Studio", "간결한 콘텐츠",
                "Local Demo", "/images/cms/hero-bio.svg", "Technology", "소개",
                "자세히 보기", "/about", false, Instant.now());
        when(repository.templateExists("MINIMAL")).thenReturn(true);
        when(repository.findTemplate("MINIMAL")).thenReturn(Optional.of(saved));

        TemplateView result = service.saveTemplate(
                "MINIMAL", "MINIMAL", "#0e9f76", " AX Studio ", "간결한 콘텐츠",
                "Local Demo", "/images/cms/hero-bio.svg", "Technology", "소개",
                "자세히 보기", "/about");

        assertThat(result).isEqualTo(saved);
        verify(repository).templateExists("MINIMAL");
        verify(repository).updateTemplate(
                "MINIMAL", "MINIMAL", "#0E9F76", "AX Studio", "간결한 콘텐츠",
                "Local Demo", "/images/cms/hero-bio.svg", "Technology", "소개",
                "자세히 보기", "/about");
        verify(repository).findTemplate("MINIMAL");
        verifyNoMoreInteractions(repository);
        verifyNoInteractions(siteSettings);
    }

    @Test
    void deletesChildMenusBeforeTheParentSoNoneIsPromoted() {
        MenuView parent = new MenuView(10L, "소개", "/about", null, 10, "NONE", null);
        MenuView company = new MenuView(11L, "회사 소개", "/about/company", 10L, 11, "CONTENT", 20L);
        MenuView vision = new MenuView(12L, "비전", "/about/vision", 10L, 12, "CONTENT", 21L);
        when(repository.findMenu(10L)).thenReturn(Optional.of(parent));
        when(repository.findChildMenus(10L)).thenReturn(List.of(company, vision));
        when(repository.deleteMenu(11L)).thenReturn(1);
        when(repository.deleteMenu(12L)).thenReturn(1);
        when(repository.deleteMenu(10L)).thenReturn(1);

        service.deleteMenu(10L);

        InOrder order = inOrder(repository);
        order.verify(repository).deleteMenu(11L);
        order.verify(repository).deleteMenu(12L);
        order.verify(repository).deleteMenu(10L);
    }

    @Test
    void deletesLeafMenuWithoutTouchingOtherRows() {
        MenuView leaf = new MenuView(12L, "비전", "/about/vision", 10L, 12, "CONTENT", 21L);
        when(repository.findMenu(12L)).thenReturn(Optional.of(leaf));
        when(repository.findChildMenus(12L)).thenReturn(List.of());
        when(repository.deleteMenu(12L)).thenReturn(1);

        service.deleteMenu(12L);

        verify(repository).deleteMenu(12L);
    }

    @Test
    void rejectsDeletingMenuThatDoesNotExist() {
        when(repository.findMenu(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteMenu(99L))
                .isInstanceOf(CmsServiceException.class)
                .hasMessage("메뉴를 찾을 수 없습니다.");
    }

    /**
     * 이미지 형식은 파일 앞부분 바이트로 가린다.
     *
     * <p>확장자와 요청이 알려준 타입은 바꿔 보낼 수 있어 믿지 않는다.
     */
    @Test
    void storesImageTypeReadFromTheFileItselfRatherThanTheRequest() {
        UUID author = UUID.randomUUID();
        byte[] png = bytesOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x01);
        when(repository.insertContentImage(author, "image/png", png))
                .thenReturn(new ContentImageView(12L, "image/png", png.length));

        ContentImageView saved = service.createContentImage(author, png);

        assertThat(saved.id()).isEqualTo(12L);
        verify(repository).insertContentImage(author, "image/png", png);
    }

    @Test
    void readsWebpFromItsRiffContainerRatherThanTheFirstFourBytesAlone() {
        UUID author = UUID.randomUUID();
        byte[] webp = bytesOf(
                0x52, 0x49, 0x46, 0x46, 0x00, 0x00, 0x00, 0x00, 0x57, 0x45, 0x42, 0x50);
        when(repository.insertContentImage(author, "image/webp", webp))
                .thenReturn(new ContentImageView(13L, "image/webp", webp.length));

        assertThat(service.createContentImage(author, webp).contentType()).isEqualTo("image/webp");
    }

    /** SVG는 그 안에 스크립트를 담을 수 있어 허용 목록에서 뺐다. */
    @Test
    void refusesAnSvgEvenThoughItIsAnImageFormat() {
        byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);

        assertThatThrownBy(() -> service.createContentImage(UUID.randomUUID(), svg))
                .isInstanceOf(CmsServiceException.class)
                .hasMessageContaining("JPG, PNG, WebP");
        verifyNoInteractions(repository);
    }

    @Test
    void refusesAFileThatIsNotAnImageAtAll() {
        byte[] text = "회사 소개 초안".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        assertThatThrownBy(() -> service.createContentImage(UUID.randomUUID(), text))
                .isInstanceOf(CmsServiceException.class)
                .hasMessageContaining("JPG, PNG, WebP");
        verifyNoInteractions(repository);
    }

    @Test
    void refusesAnImageOverTheSizeLimitBeforeTouchingTheDatabase() {
        byte[] huge = new byte[8 * 1024 * 1024 + 1];
        huge[0] = (byte) 0xFF;
        huge[1] = (byte) 0xD8;
        huge[2] = (byte) 0xFF;

        assertThatThrownBy(() -> service.createContentImage(UUID.randomUUID(), huge))
                .isInstanceOf(CmsServiceException.class)
                .hasMessageContaining("8MB");
        verifyNoInteractions(repository);
    }

    @Test
    void reportsAMissingImageAsNotFound() {
        when(repository.findContentImage(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.contentImage(99L))
                .isInstanceOf(CmsServiceException.class)
                .extracting(failure -> ((CmsServiceException) failure).kind())
                .isEqualTo(CmsServiceException.Kind.NOT_FOUND);
    }

    private static byte[] bytesOf(int... values) {
        byte[] bytes = new byte[values.length];
        for (int index = 0; index < values.length; index++) {
            bytes[index] = (byte) values[index];
        }
        return bytes;
    }
}

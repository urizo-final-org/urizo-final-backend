package org.urizo.axmodulestudio.backend.cms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.urizo.axmodulestudio.backend.cms.dto.CmsRequests.TemplateRequest;
import org.urizo.axmodulestudio.backend.cms.dto.CmsResponses.TemplateView;
import org.urizo.axmodulestudio.backend.cms.dto.TemplateHeroImage;
import org.urizo.axmodulestudio.backend.cms.repository.CmsRepository;

class CmsTemplateImagesTest {
    private final CmsRepository repository = mock(CmsRepository.class);
    private final CmsService service = new CmsService(repository, mock(CmsCodeService.class));

    private void current(List<String> images) {
        when(repository.findTemplate("BOLD")).thenReturn(Optional.of(new TemplateView(
                "BOLD", "MINIMAL", "#112233", "Site", "Header", "Footer", "/old.png",
                "Title", "Subtitle", "More", "/about", false, Instant.EPOCH, images)));
    }

    private void save(String first, List<String> images) {
        service.saveTemplate("BOLD", "MINIMAL", "#112233", "Site", "Header", "Footer",
                first, "Title", "Subtitle", "More", "/about", images);
    }

    private void savedImages(String first, List<String> images) {
        verify(repository).updateTemplate("BOLD", "MINIMAL", "#112233", "Site", "Header", "Footer",
                first, "Title", "Subtitle", "More", "/about", images.stream().map(url -> new TemplateHeroImage(url, "", "")).toList());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5})
    void storesTheExplicitOrderAndDerivesTheLegacyFirstUrl(int count) {
        current(List.of("/old.png", "/keep.png"));
        List<String> images = java.util.stream.IntStream.range(0, count).mapToObj(i -> "/" + i + ".png").toList();
        save("/stale.png", images);
        savedImages(count == 0 ? "" : images.get(0), images);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "/old.png"})
    void omittedListKeepsExistingImagesForOlderClients(String first) {
        current(List.of("/old.png", "/keep.png"));
        save(first, null);
        savedImages("/old.png", List.of("/old.png", "/keep.png"));
    }

    @Test
    void oldScalarWriterReplacesOnlyTheFirstImage() {
        current(List.of("/old.png", "/keep.png"));
        service.saveTemplate("BOLD", "MINIMAL", "#112233", "Site", "Header", "Footer",
                " /new.png ", "Title", "Subtitle", "More", "/about");
        savedImages("/new.png", List.of("/new.png", "/keep.png"));
    }

    @Test
    void nullableStorageFallsBackToTheOriginalStaticUrl() {
        current(null);
        save(null, null);
        savedImages("/old.png", List.of("/old.png"));
    }

    @Test
    void emptyListDoesNotFallBackToTheOldUrl() {
        current(List.of());
        save(null, null);
        savedImages("", List.of());
    }

    @Test
    void normalizesAddressesWithoutReordering() {
        current(null);
        save(null, List.of(" /b.png ", " /a.png "));
        savedImages("/b.png", List.of("/b.png", "/a.png"));
    }

    @Test
    void invalidListsNeverWrite() {
        current(null);
        for (List<String> images : List.of(List.of("1", "2", "3", "4", "5", "6"),
                List.of(" "), List.of("x".repeat(501)), Arrays.asList("/ok.png", null))) {
            assertThatThrownBy(() -> save(null, images)).isInstanceOf(CmsServiceException.class);
        }
        verify(repository, never()).updateTemplate(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void jsonDistinguishesMissingAndEmptyAndValidatesEachElement() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String base = "\"layout\":\"BOLD\",\"primaryColor\":\"#112233\",\"siteName\":\"Site\",\"heroTitle\":\"Title\"";
        assertThat(mapper.readValue("{" + base + "}", TemplateRequest.class).heroImageUrls()).isNull();
        assertThat(mapper.readValue("{" + base + ",\"heroImageUrls\":[]}", TemplateRequest.class).heroImageUrls()).isEmpty();
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            for (String list : List.of("[\"\"]", "[null]", "[\"1\",\"2\",\"3\",\"4\",\"5\",\"6\"]")) {
                assertThat(validator.validate(mapper.readValue("{" + base + ",\"heroImageUrls\":" + list + "}", TemplateRequest.class))).isNotEmpty();
            }
            assertThat(validator.validate(mapper.readValue("{" + base + ",\"heroImageUrls\":[]}", TemplateRequest.class))).isEmpty();
        }
    }

    @Test
    void captionsAreAuthoritativeAndNormalized() {
        current(List.of("/old.png"));
        service.saveTemplate("BOLD", "MINIMAL", "#112233", "Site", "Header", "Footer",
                "/stale.png", "Title", "Subtitle", "More", "/about", List.of("/ignored.png"),
                List.of(new TemplateHeroImage(" /sea.png ", " 바다로 ", " 오늘의 여행 ")));
        verify(repository).updateTemplate("BOLD", "MINIMAL", "#112233", "Site", "Header", "Footer",
                "/sea.png", "Title", "Subtitle", "More", "/about",
                List.of(new TemplateHeroImage("/sea.png", "바다로", "오늘의 여행")));
    }

    @Test
    void legacyReorderPreservesCaptionsByUrlOccurrence() {
        var first = new TemplateHeroImage("/same.png", "First", "one");
        var second = new TemplateHeroImage("/same.png", "Second", "two");
        var other = new TemplateHeroImage("/other.png", "Other", "three");
        when(repository.findTemplate("BOLD")).thenReturn(Optional.of(new TemplateView(
                "BOLD", "MINIMAL", "#112233", "Site", "Header", "Footer", "/same.png",
                "Title", "Subtitle", "More", "/about", false, Instant.EPOCH, null, List.of(first, second, other))));
        save(null, List.of("/other.png", "/same.png", "/same.png"));
        verify(repository).updateTemplate("BOLD", "MINIMAL", "#112233", "Site", "Header", "Footer",
                "/other.png", "Title", "Subtitle", "More", "/about", List.of(other, first, second));
    }

    @Test
    void rejectsOversizeOrMissingCaptionImagesWithoutWriting() {
        current(null);
        for (List<TemplateHeroImage> images : List.of(
                List.of(new TemplateHeroImage("/a", "x".repeat(121), "")),
                List.of(new TemplateHeroImage("/a", "", "x".repeat(241))),
                Arrays.asList((TemplateHeroImage) null),
                java.util.Collections.nCopies(6, new TemplateHeroImage("/a", "", "")))) {
            assertThatThrownBy(() -> service.saveTemplate("BOLD", "MINIMAL", "#112233", "Site", "Header", "Footer",
                    null, "Title", "Subtitle", "More", "/about", null, images)).isInstanceOf(CmsServiceException.class);
        }
        verify(repository, never()).updateTemplate(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void jsonValidatesNestedCaptionFieldsAndAllowsExplicitEmpty() throws Exception {
        String base = "\"layout\":\"BOLD\",\"primaryColor\":\"#112233\",\"siteName\":\"Site\",\"heroTitle\":\"Title\"";
        var mapper = new ObjectMapper();
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            for (String images : List.of("[null]", "[{\"url\":\" \"}]",
                    "[{\"url\":\"/a\",\"title\":\"" + "x".repeat(121) + "\"}]")) {
                var request = mapper.readValue("{" + base + ",\"heroImages\":" + images + "}", TemplateRequest.class);
                assertThat(factory.getValidator().validate(request)).isNotEmpty();
            }
            var empty = mapper.readValue("{" + base + ",\"heroImages\":[]}", TemplateRequest.class);
            assertThat(empty.heroImages()).isEmpty();
            assertThat(factory.getValidator().validate(empty)).isEmpty();
        }
    }
}

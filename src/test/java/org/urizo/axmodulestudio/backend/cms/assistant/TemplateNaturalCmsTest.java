package org.urizo.axmodulestudio.backend.cms.assistant;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.urizo.axmodulestudio.backend.cms.dto.CmsRequests;
import org.urizo.axmodulestudio.backend.cms.dto.CmsResponses.TemplateView;
import org.urizo.axmodulestudio.backend.cms.dto.TemplateHeroImage;
import org.urizo.axmodulestudio.backend.cms.service.CmsRequestValidator;
import org.urizo.axmodulestudio.backend.cms.service.CmsService;

class TemplateNaturalCmsTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final CmsService cms = mock(CmsService.class);
    private final NaturalCmsContract.ResourceRef resource = new NaturalCmsContract.ResourceRef("TEMPLATE", "CLASSIC");
    private final List<TemplateHeroImage> images = IntStream.rangeClosed(1, 5)
            .mapToObj(i -> new TemplateHeroImage("/api/site/images/" + i, "Photo " + i, "Caption " + i)).toList();
    private TemplateView template() {
        return new TemplateView("CLASSIC", "CLASSIC", "#123456", "Site", "Header", "Footer", "", "Hero",
                "Subtitle", "Button", "/", true, Instant.parse("2026-09-11T00:00:00Z"), null, images);
    }
    private NaturalCmsResourceService resources() {
        when(cms.templates()).thenReturn(List.of(template()));
        when(cms.templateForUpdate("CLASSIC")).thenReturn(template());
        return new NaturalCmsResourceService(cms, mock(CmsRequestValidator.class), mapper);
    }
    private JsonNode command(String fields) throws Exception {
        return mapper.readTree("{\"operation\":\"UPDATE\",\"fields\":" + fields + "}");
    }
    @Test void partialUpdatePreservesAllFiveImagesAndReadonlySiteName() throws Exception {
        var service = resources();
        var value = service.validateCommand(resource, command("{\"heroTitle\":\" New hero \"}"));
        assertThat(value.path("fields").path("heroTitle").asText()).isEqualTo("New hero");
        verify(cms, never()).templateForUpdate(anyString());
        service.applyApprovedTemplate(resource, value, UUID.randomUUID(), "", service.snapshot(resource));
        verify(cms).saveTemplate("CLASSIC", "CLASSIC", "#123456", "Site", "Header", "Footer",
                images.get(0).url(), "New hero", "Subtitle", "Button", "/",
                images.stream().map(TemplateHeroImage::url).toList(), images);
    }
    @Test void acceptsReorderCaptionsUnlinkAndSuppliedReplacementOnly() throws Exception {
        var service = resources();
        var fields = mapper.createObjectNode();
        var ordered = fields.putArray("heroImages");
        for (int i = 4; i >= 0; i--) ordered.addObject().put("url", images.get(i).url()).put("title", "Updated " + i).put("description", "New caption");
        ObjectNode value = mapper.createObjectNode().put("operation", "UPDATE"); value.set("fields", fields);
        assertThat(service.validateCommand(resource, value)).isEqualTo(value);
        assertThat(service.validateCommand(resource, command("{\"heroImages\":[]}"))).isNotNull();
        var replacement = command("{\"heroImages\":[{\"url\":\"/api/site/images/6\",\"title\":\"New\",\"description\":\"Caption\"}]}");
        assertThatThrownBy(() -> service.validateCommand(resource, replacement)).isInstanceOf(NaturalCmsException.class);
        when(cms.contentImageExists(6)).thenReturn(true);
        assertThat(service.validateCommand(resource, replacement, "바꿔 줘\n\n[이 요청에 첨부한 사진 주소: /api/site/images/6]")).isEqualTo(replacement);
        when(cms.contentImageExists(6)).thenReturn(false);
        assertThatThrownBy(() -> service.validateCommand(resource, replacement, "바꿔 줘\n\n[이 요청에 첨부한 사진 주소: /api/site/images/6]")).isInstanceOf(NaturalCmsException.class);
    }
    @ParameterizedTest @ValueSource(strings = {
        "{\"siteName\":\"Other\"}", "{\"templateKey\":\"BOLD\"}", "{\"heroImageUrl\":\"x\"}",
        "{\"layout\":\"CUSTOM\"}", "{\"heroImages\":null}", "{\"heroImages\":[\"x\"]}",
        "{\"heroImages\":[{\"url\":\"/api/site/images/1\",\"title\":\"x\"}]}",
        "{\"heroButtonUrl\":\"https://example.com\"}", "{\"heroButtonUrl\":\"/invented\"}", "{\"body\":\"x\"}"
    }) void rejectsUnsupportedFieldsTypesAndInventedRoutes(String fields) throws Exception {
        var service = resources();
        assertThatThrownBy(() -> service.validateCommand(resource, command(fields))).isInstanceOf(NaturalCmsException.class);
    }
    @Test void rejectsStaleLockedStateBeforeSaving() throws Exception {
        var service = resources();
        ObjectNode before = service.snapshot(resource); before.put("updatedAt", "old");
        assertThatThrownBy(() -> service.applyApprovedTemplate(resource, command("{\"heroTitle\":\"New\"}"), UUID.randomUUID(), "", before))
                .isInstanceOf(NaturalCmsException.class).hasMessageContaining("변경됐습니다");
        verify(cms, never()).saveTemplate(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyList(), anyList());
    }
    @Test void validatesActualCmsDtoConstraints() throws Exception {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            when(cms.templates()).thenReturn(List.of(template()));
            var service = new NaturalCmsResourceService(cms, new CmsRequestValidator(factory.getValidator()), mapper);
            assertThatCode(() -> service.validateCommand(resource, command("{\"primaryColor\":\"#aabbcc\",\"heroButtonUrl\":\"/search\"}"))).doesNotThrowAnyException();
            assertThatThrownBy(() -> service.validateCommand(resource, command("{\"primaryColor\":\"red\"}"))).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> service.validateCommand(resource, command("{\"heroTitle\":\"\"}"))).isInstanceOf(RuntimeException.class);
        }
    }
    @Test void imageLimitsRejectSixPhotosAndOversizeCaption() {
        ObjectNode fields = mapper.createObjectNode(); var array = fields.putArray("heroImages");
        for (int i = 0; i < 6; i++) array.add(mapper.valueToTree(images.get(0)));
        assertThat(TemplateCommandPolicy.imageList(array)).isFalse();
        array.remove(5); ((ObjectNode) array.get(0)).put("description", "x".repeat(241));
        assertThat(TemplateCommandPolicy.imageList(array)).isFalse();
    }
    @Test void allowsAnExistingBundledPhotoAndNormalizesColorBeforePreview() throws Exception {
        var current = new TemplateView("CLASSIC", "CLASSIC", "#123456", "Site", "", "", "/images/cms/hero-bio.svg",
                "Hero", "", "", "", true, Instant.parse("2026-09-11T00:00:00Z"));
        var service = resources(); when(cms.templates()).thenReturn(List.of(current));
        var command = command("{\"primaryColor\":\"#aabbcc\",\"heroImages\":[{\"url\":\"/images/cms/hero-bio.svg\",\"title\":\"Caption\",\"description\":\"\"}]}");
        assertThat(service.validateCommand(resource, command).path("fields").path("primaryColor").asText()).isEqualTo("#AABBCC");
    }
}

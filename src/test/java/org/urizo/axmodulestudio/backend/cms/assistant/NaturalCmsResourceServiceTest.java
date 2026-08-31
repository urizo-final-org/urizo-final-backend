package org.urizo.axmodulestudio.backend.cms.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.urizo.axmodulestudio.backend.cms.dto.CmsRequests;
import org.urizo.axmodulestudio.backend.cms.dto.CmsResponses.ContentView;
import org.urizo.axmodulestudio.backend.cms.service.CmsRequestValidator;
import org.urizo.axmodulestudio.backend.cms.service.CmsService;

class NaturalCmsResourceServiceTest {

    private static final NaturalCmsContract.ResourceRef RESOURCE =
            new NaturalCmsContract.ResourceRef("CONTENT", "7");

    private static ContentView content(String title, String body) {
        return new ContentView(
                7,
                UUID.fromString("11111111-1111-4111-8111-111111111111"),
                "Admin",
                title,
                body,
                Instant.parse("2026-08-30T00:00:00Z"),
                Instant.parse("2026-08-30T00:01:00Z"));
    }

    @Test
    void validatesWithoutMutationAndAppliesThroughTheExistingCmsService() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        CmsService cms = mock(CmsService.class);
        CmsRequestValidator validator = mock(CmsRequestValidator.class);
        NaturalCmsResourceService resources =
                new NaturalCmsResourceService(cms, validator, mapper);
        when(cms.content(7)).thenReturn(content("Old title", "Old body"));
        JsonNode command = mapper.readTree("""
                {"operation":"UPDATE","fields":{"title":"New title","body":"New body"}}
                """);

        JsonNode validated = resources.validateCommand(RESOURCE, command);

        assertThat(validated).isEqualTo(command);
        ArgumentCaptor<CmsRequests.ArticleRequest> request =
                ArgumentCaptor.forClass(CmsRequests.ArticleRequest.class);
        verify(validator).validate(request.capture());
        assertThat(request.getValue().title()).isEqualTo("New title");
        assertThat(request.getValue().body()).isEqualTo("New body");
        verify(cms, never()).updateContent(7, "New title", "New body");

        when(cms.updateContent(7, "New title", "New body"))
                .thenReturn(content("New title", "New body"));

        JsonNode result = resources.apply(RESOURCE, command);

        assertThat(result.path("id").asLong()).isEqualTo(7);
        assertThat(result.path("title").asText()).isEqualTo("New title");
        verify(cms).updateContent(7, "New title", "New body");
    }

    @Test
    void keepsTheCurrentValueForFieldsTheCommandDoesNotSend() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        CmsService cms = mock(CmsService.class);
        NaturalCmsResourceService resources =
                new NaturalCmsResourceService(cms, mock(CmsRequestValidator.class), mapper);
        when(cms.content(7)).thenReturn(content("Old title", "Old body"));
        when(cms.updateContent(7, "New title", "Old body"))
                .thenReturn(content("New title", "Old body"));
        JsonNode command = mapper.readTree("""
                {"operation":"UPDATE","fields":{"title":"New title"}}
                """);

        resources.apply(RESOURCE, command);

        verify(cms).updateContent(7, "New title", "Old body");
    }

    @Test
    void rejectsFieldNamesOutsideTheContentSchema() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        CmsService cms = mock(CmsService.class);
        NaturalCmsResourceService resources =
                new NaturalCmsResourceService(cms, mock(CmsRequestValidator.class), mapper);
        JsonNode command = mapper.readTree("""
                {"operation":"UPDATE","fields":{"title":"New title","author":"Someone"}}
                """);

        assertThatThrownBy(() -> resources.validateCommand(RESOURCE, command))
                .isInstanceOf(NaturalCmsException.class)
                .hasMessageContaining("title and body");
    }

    @Test
    void rejectsAnEmptyFieldSet() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        NaturalCmsResourceService resources = new NaturalCmsResourceService(
                mock(CmsService.class), mock(CmsRequestValidator.class), mapper);
        JsonNode command = mapper.readTree("""
                {"operation":"UPDATE","fields":{}}
                """);

        assertThatThrownBy(() -> resources.validateCommand(RESOURCE, command))
                .isInstanceOf(NaturalCmsException.class);
    }

    @Test
    void acceptsTheThreeSupportedMarkdownForms() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        CmsService cms = mock(CmsService.class);
        NaturalCmsResourceService resources =
                new NaturalCmsResourceService(cms, mock(CmsRequestValidator.class), mapper);
        when(cms.content(7)).thenReturn(content("Old title", "Old body"));
        JsonNode command = mapper.readTree("""
                {"operation":"UPDATE","fields":{"body":"## 제목\\n\\n**강조** 문구입니다.\\n\\n- 항목 하나\\n- 항목 둘"}}
                """);

        assertThatCode(() -> resources.validateCommand(RESOURCE, command)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "# 제목", "### 제목", "> 인용", "* 목록", "+ 목록", "1. 순서 목록",
        "```java", "| 표 |", "---", "![그림](/a.png)", "[링크](/a)",
    })
    void rejectsMarkdownTheEditorCannotRender(String line) throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        CmsService cms = mock(CmsService.class);
        NaturalCmsResourceService resources =
                new NaturalCmsResourceService(cms, mock(CmsRequestValidator.class), mapper);
        when(cms.content(7)).thenReturn(content("Old title", "Old body"));
        JsonNode command = mapper.valueToTree(java.util.Map.of(
                "operation", "UPDATE", "fields", java.util.Map.of("body", "본문\n" + line)));

        assertThatThrownBy(() -> resources.validateCommand(RESOURCE, command))
                .isInstanceOf(NaturalCmsException.class)
                .hasMessageContaining("headings (##)");
    }
}

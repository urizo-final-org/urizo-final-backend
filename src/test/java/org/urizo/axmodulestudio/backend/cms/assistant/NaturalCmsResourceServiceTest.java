package org.urizo.axmodulestudio.backend.cms.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.urizo.axmodulestudio.backend.cms.dto.CmsRequests;
import org.urizo.axmodulestudio.backend.cms.dto.CmsResponses.ContentView;
import org.urizo.axmodulestudio.backend.cms.service.CmsRequestValidator;
import org.urizo.axmodulestudio.backend.cms.service.CmsService;

class NaturalCmsResourceServiceTest {

    private static final NaturalCmsContract.ResourceRef RESOURCE =
            new NaturalCmsContract.ResourceRef("CONTENT", "7");

    @Test
    void validatesWithoutMutationAndAppliesThroughTheExistingCmsService() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        CmsService cms = mock(CmsService.class);
        CmsRequestValidator validator = mock(CmsRequestValidator.class);
        NaturalCmsResourceService resources =
                new NaturalCmsResourceService(cms, validator, mapper);
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

        ContentView applied = new ContentView(
                7,
                UUID.fromString("11111111-1111-4111-8111-111111111111"),
                "Admin",
                "New title",
                "New body",
                Instant.parse("2026-08-30T00:00:00Z"),
                Instant.parse("2026-08-30T00:01:00Z"));
        when(cms.updateContent(7, "New title", "New body")).thenReturn(applied);

        JsonNode result = resources.apply(RESOURCE, command);

        assertThat(result.path("id").asLong()).isEqualTo(7);
        assertThat(result.path("title").asText()).isEqualTo("New title");
        verify(cms).updateContent(7, "New title", "New body");
    }
}

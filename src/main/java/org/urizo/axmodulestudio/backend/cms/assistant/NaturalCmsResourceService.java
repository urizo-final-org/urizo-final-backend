package org.urizo.axmodulestudio.backend.cms.assistant;

import java.util.HashSet;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.urizo.axmodulestudio.backend.cms.dto.CmsRequests;
import org.urizo.axmodulestudio.backend.cms.dto.CmsResponses.ContentView;
import org.urizo.axmodulestudio.backend.cms.service.CmsRequestValidator;
import org.urizo.axmodulestudio.backend.cms.service.CmsService;

@Service
@Profile("local-full")
public final class NaturalCmsResourceService {

    private final CmsService cmsService;
    private final CmsRequestValidator requestValidator;
    private final ObjectMapper objectMapper;

    public NaturalCmsResourceService(
            CmsService cmsService,
            CmsRequestValidator requestValidator,
            ObjectMapper objectMapper) {
        this.cmsService = cmsService;
        this.requestValidator = requestValidator;
        this.objectMapper = objectMapper;
    }

    public ObjectNode snapshot(NaturalCmsContract.ResourceRef resource) {
        ContentView content = cmsService.content(contentId(resource));
        ObjectNode state = objectMapper.createObjectNode();
        state.put("id", content.id());
        state.put("title", content.title());
        state.put("body", content.body());
        state.put("updatedAt", content.updatedAt().toString());
        return state;
    }

    public JsonNode validateCommand(
            NaturalCmsContract.ResourceRef resource, JsonNode command) {
        contentId(resource);
        if (command == null
                || !command.isObject()
                || fields(command).equals(Set.of("operation", "fields")) == false
                || !"UPDATE".equals(command.path("operation").asText())
                || !command.path("fields").isObject()
                || fields(command.path("fields")).equals(Set.of("title", "body")) == false
                || !command.path("fields").path("title").isTextual()
                || !command.path("fields").path("body").isTextual()) {
            throw new NaturalCmsException(
                    "CMS_COMMAND_INVALID",
                    "The Natural CMS command is not an approved CONTENT update.",
                    org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY);
        }
        CmsRequests.ArticleRequest request = new CmsRequests.ArticleRequest(
                command.path("fields").path("title").asText(),
                command.path("fields").path("body").asText());
        requestValidator.validate(request);
        return command.deepCopy();
    }

    public JsonNode apply(
            NaturalCmsContract.ResourceRef resource, JsonNode command) {
        JsonNode validated = validateCommand(resource, command);
        ContentView applied = cmsService.updateContent(
                contentId(resource),
                validated.path("fields").path("title").asText(),
                validated.path("fields").path("body").asText());
        return objectMapper.valueToTree(applied);
    }

    private static long contentId(NaturalCmsContract.ResourceRef resource) {
        if (!"CONTENT".equals(resource.type())) {
            throw new NaturalCmsException(
                    "CMS_RESOURCE_UNSUPPORTED",
                    "The Natural CMS proposal currently supports CONTENT only.",
                    org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY);
        }
        try {
            long id = Long.parseLong(resource.id());
            if (id < 1) {
                throw new NumberFormatException();
            }
            return id;
        }
        catch (NumberFormatException failure) {
            throw new NaturalCmsException(
                    "CMS_RESOURCE_INVALID",
                    "The Natural CMS CONTENT id is invalid.",
                    org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    private static Set<String> fields(JsonNode value) {
        Set<String> names = new HashSet<>();
        value.fieldNames().forEachRemaining(names::add);
        return Set.copyOf(names);
    }
}

package org.urizo.axmodulestudio.backend.cms.assistant;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

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

    private static final Set<String> CONTENT_FIELDS = Set.of("title", "body");

    /**
     * 에디터가 지원하지 않는 줄 시작 문법. 순서 목록, 인용, 코드 블록, 표, 구분선,
     * {@code ## } 외 제목, {@code - } 외 글머리 기호를 막는다.
     */
    private static final Pattern UNSUPPORTED_LINE = Pattern.compile(
            "^(?:#(?!# )|#{3,}|>|\\*\\s|\\+\\s|\\d+\\.\\s|```|~~~|\\||-{3,}|!\\[)");

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

    /**
     * 구조를 확인한 뒤 현재 값과 병합해 검사한다.
     *
     * <p>바꿀 필드만 보내도 되며, 보내지 않은 필드는 현재 값을 유지한다.
     * 본문이 있으면 허용 문법만 쓰였는지 함께 본다.
     */
    public JsonNode validateCommand(
            NaturalCmsContract.ResourceRef resource, JsonNode command) {
        long id = contentId(resource);
        if (command == null
                || !command.isObject()
                || fields(command).equals(Set.of("operation", "fields")) == false
                || !"UPDATE".equals(command.path("operation").asText())
                || !command.path("fields").isObject()) {
            throw invalidCommand("The Natural CMS command is not an approved CONTENT update.");
        }
        Set<String> names = fields(command.path("fields"));
        if (names.isEmpty() || !CONTENT_FIELDS.containsAll(names)) {
            throw invalidCommand("CONTENT update accepts the title and body fields only.");
        }
        for (String name : names) {
            if (!command.path("fields").path(name).isTextual()) {
                throw invalidCommand("The " + name + " field must be text.");
            }
        }
        requestValidator.validate(merged(id, command.path("fields")));
        return command.deepCopy();
    }

    public JsonNode apply(
            NaturalCmsContract.ResourceRef resource, JsonNode command) {
        long id = contentId(resource);
        JsonNode validated = validateCommand(resource, command);
        CmsRequests.ArticleRequest request = merged(id, validated.path("fields"));
        ContentView applied = cmsService.updateContent(id, request.title(), request.body());
        return objectMapper.valueToTree(applied);
    }

    /** 보내지 않은 필드는 현재 값을 그대로 쓴다. */
    private CmsRequests.ArticleRequest merged(long id, JsonNode fields) {
        ContentView current = cmsService.content(id);
        String title = fields.hasNonNull("title") ? fields.path("title").asText() : current.title();
        String body = fields.hasNonNull("body") ? fields.path("body").asText() : current.body();
        requireSupportedMarkdown(body);
        return new CmsRequests.ArticleRequest(title, body);
    }

    /**
     * 본문은 제목 {@code ## }, 강조 {@code **문구**}, 목록 {@code - } 셋만 쓴다.
     *
     * <p>에디터가 지원하지 않는 문법이 들어오면 사용자 화면에서 원문 그대로 노출된다.
     * 저장 전에 막는 편이 낫다.
     */
    private static void requireSupportedMarkdown(String body) {
        if (body == null) {
            return;
        }
        for (String line : body.split("\n", -1)) {
            String trimmed = line.strip();
            if (UNSUPPORTED_LINE.matcher(trimmed).find() || trimmed.contains("](")) {
                throw invalidCommand(
                        "The body may use headings (##), emphasis (**text**) and list items (-) only.");
            }
        }
    }

    private static NaturalCmsException invalidCommand(String message) {
        return new NaturalCmsException(
                "CMS_COMMAND_INVALID", message, org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY);
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

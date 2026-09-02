package org.urizo.axmodulestudio.backend.cms.assistant;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.urizo.axmodulestudio.backend.cms.dto.CmsRequests;
import org.urizo.axmodulestudio.backend.cms.dto.CmsResponses.BoardView;
import org.urizo.axmodulestudio.backend.cms.dto.CmsResponses.ContentView;
import org.urizo.axmodulestudio.backend.cms.dto.CmsResponses.MenuView;
import org.urizo.axmodulestudio.backend.cms.dto.CmsResponses.TemplateView;
import org.urizo.axmodulestudio.backend.cms.service.CmsRequestValidator;
import org.urizo.axmodulestudio.backend.cms.service.CmsService;

/**
 * Resource 타입별 상태 조회·검증·반영을 한 곳에 모은다.
 *
 * <p>리소스를 하나 늘리려면 {@link ResourceHandler} 구현 하나를 만들고 {@code handlers}에 등록한다.
 * 검증과 반영이 같은 {@link ResourceHandler#merged} 결과를 쓰므로 두 경로가 어긋나지 않는다.
 */
@Service
@Profile("local-full")
public final class NaturalCmsResourceService {

    /**
     * 에디터가 지원하지 않는 줄 시작 문법. 순서 목록, 인용, 코드 블록, 표, 구분선,
     * {@code ## } 외 제목, {@code - } 외 글머리 기호를 막는다.
     */
    private static final Pattern UNSUPPORTED_LINE = Pattern.compile(
            "^(?:#(?!# )|#{3,}|>|\\*\\s|\\+\\s|\\d+\\.\\s|```|~~~|\\||-{3,}|!\\[)");

    private final CmsService cmsService;
    private final CmsRequestValidator requestValidator;
    private final ObjectMapper objectMapper;
    private final Map<String, ResourceHandler<?>> handlers;

    public NaturalCmsResourceService(
            CmsService cmsService,
            CmsRequestValidator requestValidator,
            ObjectMapper objectMapper) {
        this.cmsService = cmsService;
        this.requestValidator = requestValidator;
        this.objectMapper = objectMapper;
        this.handlers = Map.of(
                "MENU", new MenuHandler(),
                "BOARD", new BoardHandler(),
                "CONTENT", new ContentHandler(),
                "TEMPLATE", new TemplateHandler());
    }

    public ObjectNode snapshot(NaturalCmsContract.ResourceRef resource) {
        return handler(resource).snapshot(resource.id());
    }

    /**
     * 구조를 확인한 뒤 현재 값과 병합해 검사한다.
     *
     * <p>바꿀 필드만 보내도 되며, 보내지 않은 필드는 현재 값을 유지한다.
     */
    public JsonNode validateCommand(
            NaturalCmsContract.ResourceRef resource, JsonNode command) {
        ResourceHandler<?> handler = handler(resource);
        validated(handler, resource.id(), commandFields(resource.type(), handler, command));
        return command.deepCopy();
    }

    public JsonNode apply(
            NaturalCmsContract.ResourceRef resource, JsonNode command) {
        ResourceHandler<?> handler = handler(resource);
        return saveChecked(
                handler, resource.id(), commandFields(resource.type(), handler, command));
    }

    private <R> JsonNode saveChecked(ResourceHandler<R> handler, String id, JsonNode fields) {
        return handler.save(id, validated(handler, id, fields));
    }

    private <R> R validated(ResourceHandler<R> handler, String id, JsonNode fields) {
        R request = handler.merged(id, fields);
        requestValidator.validate(request);
        return request;
    }

    private ResourceHandler<?> handler(NaturalCmsContract.ResourceRef resource) {
        ResourceHandler<?> handler = handlers.get(resource.type());
        if (handler == null) {
            throw new NaturalCmsException(
                    "CMS_RESOURCE_UNSUPPORTED",
                    "The Natural CMS proposal does not support " + resource.type() + ".",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        return handler;
    }

    private static JsonNode commandFields(
            String type, ResourceHandler<?> handler, JsonNode command) {
        if (command == null
                || !command.isObject()
                || !names(command).equals(Set.of("operation", "fields"))
                || !"UPDATE".equals(command.path("operation").asText())
                || !command.path("fields").isObject()) {
            throw invalidCommand(
                    "The Natural CMS command is not an approved " + type + " update.");
        }
        JsonNode fields = command.path("fields");
        Set<String> given = names(fields);
        if (given.isEmpty() || !handler.fields().keySet().containsAll(given)) {
            throw invalidCommand(type + " update accepts these fields only: "
                    + String.join(", ", new TreeSet<>(handler.fields().keySet())) + ".");
        }
        for (String name : given) {
            if (!handler.fields().get(name).accepts(fields.path(name))) {
                throw invalidCommand("The " + name + " field type is invalid.");
            }
        }
        return fields;
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
                "CMS_COMMAND_INVALID", message, HttpStatus.UNPROCESSABLE_ENTITY);
    }

    private static long numericId(String id, String type) {
        try {
            long value = Long.parseLong(id);
            if (value < 1) {
                throw new NumberFormatException();
            }
            return value;
        }
        catch (NumberFormatException failure) {
            throw new NaturalCmsException(
                    "CMS_RESOURCE_INVALID",
                    "The Natural CMS " + type + " id is invalid.",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    private static Set<String> names(JsonNode value) {
        Set<String> found = new HashSet<>();
        value.fieldNames().forEachRemaining(found::add);
        return Set.copyOf(found);
    }

    /** 보내지 않은 필드는 현재 값을 그대로 쓴다. */
    private static String text(JsonNode fields, String name, String current) {
        if (!fields.has(name)) {
            return current;
        }
        JsonNode value = fields.path(name);
        return value.isNull() ? null : value.asText();
    }

    private static Long number(JsonNode fields, String name, Long current) {
        if (!fields.has(name)) {
            return current;
        }
        JsonNode value = fields.path(name);
        return value.isNull() ? null : value.asLong();
    }

    /** 필드 값이 가질 수 있는 JSON 타입. 기존 CMS 요청 DTO의 제약을 그대로 따른다. */
    private enum FieldType {
        TEXT,
        TEXT_OR_NULL,
        NUMBER,
        NUMBER_OR_NULL;

        boolean accepts(JsonNode value) {
            return switch (this) {
                case TEXT -> value.isTextual();
                case TEXT_OR_NULL -> value.isTextual() || value.isNull();
                case NUMBER -> value.isIntegralNumber();
                case NUMBER_OR_NULL -> value.isIntegralNumber() || value.isNull();
            };
        }
    }

    /**
     * 리소스 하나가 자기 필드·상태·병합·저장을 모두 안다.
     *
     * @param <R> 기존 CMS 요청 DTO. 검증과 저장이 같은 값을 쓴다.
     */
    private interface ResourceHandler<R> {

        Map<String, FieldType> fields();

        ObjectNode snapshot(String id);

        R merged(String id, JsonNode fields);

        JsonNode save(String id, R request);
    }

    private final class MenuHandler implements ResourceHandler<CmsRequests.MenuRequest> {

        @Override
        public Map<String, FieldType> fields() {
            return Map.of(
                    "name", FieldType.TEXT,
                    "path", FieldType.TEXT,
                    "parentId", FieldType.NUMBER_OR_NULL,
                    "displayOrder", FieldType.NUMBER,
                    "targetType", FieldType.TEXT,
                    "targetId", FieldType.NUMBER_OR_NULL);
        }

        @Override
        public ObjectNode snapshot(String id) {
            MenuView view = cmsService.menu(numericId(id, "MENU"));
            ObjectNode state = objectMapper.createObjectNode();
            state.put("id", view.id());
            state.put("name", view.name());
            state.put("path", view.path());
            state.put("parentId", view.parentId());
            state.put("displayOrder", view.displayOrder());
            state.put("targetType", view.targetType());
            state.put("targetId", view.targetId());
            return state;
        }

        @Override
        public CmsRequests.MenuRequest merged(String id, JsonNode fields) {
            MenuView view = cmsService.menu(numericId(id, "MENU"));
            return new CmsRequests.MenuRequest(
                    text(fields, "name", view.name()),
                    text(fields, "path", view.path()),
                    number(fields, "parentId", view.parentId()),
                    fields.has("displayOrder")
                            ? fields.path("displayOrder").asInt() : view.displayOrder(),
                    text(fields, "targetType", view.targetType()),
                    number(fields, "targetId", view.targetId()));
        }

        @Override
        public JsonNode save(String id, CmsRequests.MenuRequest request) {
            return objectMapper.valueToTree(cmsService.updateMenu(
                    numericId(id, "MENU"),
                    request.name(),
                    request.path(),
                    request.parentId(),
                    request.displayOrder(),
                    request.targetType(),
                    request.targetId()));
        }
    }

    private final class BoardHandler implements ResourceHandler<CmsRequests.BoardRequest> {

        @Override
        public Map<String, FieldType> fields() {
            return Map.of("name", FieldType.TEXT, "description", FieldType.TEXT_OR_NULL);
        }

        @Override
        public ObjectNode snapshot(String id) {
            BoardView view = cmsService.board(numericId(id, "BOARD"));
            ObjectNode state = objectMapper.createObjectNode();
            state.put("id", view.id());
            state.put("name", view.name());
            state.put("description", view.description());
            state.put("updatedAt", view.updatedAt().toString());
            return state;
        }

        @Override
        public CmsRequests.BoardRequest merged(String id, JsonNode fields) {
            BoardView view = cmsService.board(numericId(id, "BOARD"));
            return new CmsRequests.BoardRequest(
                    text(fields, "name", view.name()),
                    text(fields, "description", view.description()));
        }

        @Override
        public JsonNode save(String id, CmsRequests.BoardRequest request) {
            return objectMapper.valueToTree(cmsService.updateBoard(
                    numericId(id, "BOARD"), request.name(), request.description()));
        }
    }

    private final class ContentHandler implements ResourceHandler<CmsRequests.ArticleRequest> {

        @Override
        public Map<String, FieldType> fields() {
            return Map.of("title", FieldType.TEXT, "body", FieldType.TEXT);
        }

        @Override
        public ObjectNode snapshot(String id) {
            ContentView view = cmsService.content(numericId(id, "CONTENT"));
            ObjectNode state = objectMapper.createObjectNode();
            state.put("id", view.id());
            state.put("title", view.title());
            state.put("body", view.body());
            state.put("updatedAt", view.updatedAt().toString());
            return state;
        }

        @Override
        public CmsRequests.ArticleRequest merged(String id, JsonNode fields) {
            ContentView view = cmsService.content(numericId(id, "CONTENT"));
            String body = text(fields, "body", view.body());
            requireSupportedMarkdown(body);
            return new CmsRequests.ArticleRequest(text(fields, "title", view.title()), body);
        }

        @Override
        public JsonNode save(String id, CmsRequests.ArticleRequest request) {
            return objectMapper.valueToTree(cmsService.updateContent(
                    numericId(id, "CONTENT"), request.title(), request.body()));
        }
    }

    private final class TemplateHandler implements ResourceHandler<CmsRequests.TemplateRequest> {

        @Override
        public Map<String, FieldType> fields() {
            return Map.ofEntries(
                    Map.entry("layout", FieldType.TEXT),
                    Map.entry("primaryColor", FieldType.TEXT),
                    Map.entry("siteName", FieldType.TEXT),
                    Map.entry("headerText", FieldType.TEXT_OR_NULL),
                    Map.entry("footerText", FieldType.TEXT_OR_NULL),
                    Map.entry("heroImageUrl", FieldType.TEXT),
                    Map.entry("heroTitle", FieldType.TEXT),
                    Map.entry("heroSubtitle", FieldType.TEXT_OR_NULL),
                    Map.entry("heroButtonLabel", FieldType.TEXT_OR_NULL),
                    Map.entry("heroButtonUrl", FieldType.TEXT_OR_NULL));
        }

        @Override
        public ObjectNode snapshot(String id) {
            TemplateView view = template(id);
            ObjectNode state = objectMapper.createObjectNode();
            state.put("id", view.key());
            state.put("layout", view.layout());
            state.put("primaryColor", view.primaryColor());
            state.put("siteName", view.siteName());
            state.put("headerText", view.headerText());
            state.put("footerText", view.footerText());
            state.put("heroImageUrl", view.heroImageUrl());
            state.put("heroTitle", view.heroTitle());
            state.put("heroSubtitle", view.heroSubtitle());
            state.put("heroButtonLabel", view.heroButtonLabel());
            state.put("heroButtonUrl", view.heroButtonUrl());
            state.put("active", view.active());
            state.put("updatedAt", view.updatedAt().toString());
            return state;
        }

        @Override
        public CmsRequests.TemplateRequest merged(String id, JsonNode fields) {
            TemplateView view = template(id);
            return new CmsRequests.TemplateRequest(
                    text(fields, "layout", view.layout()),
                    text(fields, "primaryColor", view.primaryColor()),
                    text(fields, "siteName", view.siteName()),
                    text(fields, "headerText", view.headerText()),
                    text(fields, "footerText", view.footerText()),
                    text(fields, "heroImageUrl", view.heroImageUrl()),
                    text(fields, "heroTitle", view.heroTitle()),
                    text(fields, "heroSubtitle", view.heroSubtitle()),
                    text(fields, "heroButtonLabel", view.heroButtonLabel()),
                    text(fields, "heroButtonUrl", view.heroButtonUrl()));
        }

        @Override
        public JsonNode save(String id, CmsRequests.TemplateRequest request) {
            return objectMapper.valueToTree(cmsService.saveTemplate(
                    id,
                    request.layout(),
                    request.primaryColor(),
                    request.siteName(),
                    request.headerText(),
                    request.footerText(),
                    request.heroImageUrl(),
                    request.heroTitle(),
                    request.heroSubtitle(),
                    request.heroButtonLabel(),
                    request.heroButtonUrl()));
        }

        /** 템플릿은 숫자 id가 아니라 {@code key}로 찾는다. */
        private TemplateView template(String key) {
            return cmsService.templates().stream()
                    .filter(view -> view.key().equals(key))
                    .findFirst()
                    .orElseThrow(() -> new NaturalCmsException(
                            "CMS_RESOURCE_INVALID",
                            "The Natural CMS TEMPLATE id is invalid.",
                            HttpStatus.UNPROCESSABLE_ENTITY));
        }
    }
}

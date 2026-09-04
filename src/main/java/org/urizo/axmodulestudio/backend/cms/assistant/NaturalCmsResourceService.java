package org.urizo.axmodulestudio.backend.cms.assistant;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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

    /** 등록은 만들기 전이라 가리킬 id가 없다. 대상 id 자리에 고정 표식을 쓴다. */
    public static final String NEW_ID = "new";

    /** 대메뉴는 자기 구역을 갖고 하위는 그 구역 안에 들어간다. 시드 데이터의 관례 그대로다. */
    private static final int TOP_LEVEL_STEP = 10;

    /** 한 명령이 건드리는 행 상한. 자리를 옮길 때의 형제 재번호는 세지 않는다. */
    private static final int MAX_COMMAND_ROWS = 10;

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
     * 모델이 번호·경로를 지어내지 않도록 서버가 붙이는 참고 목록.
     *
     * <p>사용자에게는 보이지 않는다. 참고할 것이 없는 리소스는 {@code null}을 준다.
     */
    public ObjectNode promptContext(NaturalCmsContract.ResourceRef resource) {
        return handler(resource).promptContext(resource.id());
    }

    /**
     * 구조를 확인한 뒤 현재 값과 병합해 검사한다.
     *
     * <p>바꿀 필드만 보내도 되며, 보내지 않은 필드는 현재 값을 유지한다.
     */
    public JsonNode validateCommand(
            NaturalCmsContract.ResourceRef resource, JsonNode command) {
        ResourceHandler<?> handler = handler(resource);
        validated(handler, resource.id(), parse(resource.type(), handler, command));
        return command.deepCopy();
    }

    public JsonNode apply(
            NaturalCmsContract.ResourceRef resource, JsonNode command) {
        ResourceHandler<?> handler = handler(resource);
        return saveChecked(
                handler, resource.id(), parse(resource.type(), handler, command));
    }

    private <R> JsonNode saveChecked(ResourceHandler<R> handler, String id, Command command) {
        return handler.save(command, id, validated(handler, id, command));
    }

    /** 삭제는 병합할 값이 없다. 존재 확인만 하고 검증을 건너뛴다. */
    private <R> R validated(ResourceHandler<R> handler, String id, Command command) {
        R request = handler.merged(command, id);
        if (request != null) {
            requestValidator.validate(request);
        }
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

    /**
     * 명령서를 구조만 보고 갈라둔다. 값이 맞는지는 병합 뒤 기존 CMS 검증이 본다.
     *
     * <p>리소스마다 열린 operation이 다르다. 열리지 않은 종류는 여기서 끊는다.
     */
    private static Command parse(
            String type, ResourceHandler<?> handler, JsonNode command) {
        if (command == null
                || !command.isObject()
                || !names(command).equals(Set.of("operation", "fields"))
                || !command.path("fields").isObject()) {
            throw invalidCommand(
                    "The Natural CMS command is not an approved " + type + " command.");
        }
        String operation = command.path("operation").asText();
        if (!handler.operations().contains(operation)) {
            throw invalidCommand(type + " accepts these operations only: "
                    + String.join(", ", new TreeSet<>(handler.operations())) + ".");
        }
        JsonNode fields = command.path("fields");
        Set<String> given = names(fields);
        if ("DELETE".equals(operation)) {
            if (!given.isEmpty()) {
                throw invalidCommand("A " + type + " DELETE command carries no fields.");
            }
            return new Command(operation, fields);
        }
        if (given.isEmpty() || !handler.fields().keySet().containsAll(given)) {
            throw invalidCommand(type + " " + operation + " accepts these fields only: "
                    + String.join(", ", new TreeSet<>(handler.fields().keySet())) + ".");
        }
        for (String name : given) {
            if (!handler.fields().get(name).accepts(fields.path(name))) {
                throw invalidCommand("The " + name + " field type is invalid.");
            }
        }
        return new Command(operation, fields);
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

    /** 구조만 확인한 명령서. 대상은 명령서 밖의 {@code ResourceRef}가 가리킨다. */
    private record Command(String operation, JsonNode fields) {

        boolean creates() {
            return "CREATE".equals(operation);
        }

        boolean deletes() {
            return "DELETE".equals(operation);
        }
    }

    /**
     * 리소스 하나가 자기 필드·상태·병합·저장을 모두 안다.
     *
     * @param <R> 기존 CMS 요청 DTO. 검증과 저장이 같은 값을 쓴다.
     */
    private interface ResourceHandler<R> {

        /** 이 리소스에 열린 명령 종류. 아직 수정만 여는 리소스가 기본값이다. */
        default Set<String> operations() {
            return Set.of("UPDATE");
        }

        Map<String, FieldType> fields();

        ObjectNode snapshot(String id);

        /** 삭제는 병합할 값이 없어 {@code null}을 준다. */
        R merged(Command command, String id);

        JsonNode save(Command command, String id, R request);

        /** 모델에게 줄 참고 목록. 없으면 {@code null}. */
        default ObjectNode promptContext(String id) {
            return null;
        }
    }

    /**
     * 메뉴는 등록·수정·삭제와 자리 옮기기를 모두 받는다.
     *
     * <p>자리는 {@code position} 하나로만 주고받는다. 1부터 세는 서수이며 실제 {@code displayOrder}는
     * 코드가 계산한다. 모델에게 기존 번호 체계를 보여주지 않으므로 산술을 시킬 일이 없다.
     */
    private final class MenuHandler implements ResourceHandler<CmsRequests.MenuRequest> {

        @Override
        public Set<String> operations() {
            return Set.of("CREATE", "UPDATE", "DELETE");
        }

        @Override
        public Map<String, FieldType> fields() {
            return Map.of(
                    "name", FieldType.TEXT,
                    "path", FieldType.TEXT,
                    "parentId", FieldType.NUMBER_OR_NULL,
                    "displayOrder", FieldType.NUMBER,
                    "position", FieldType.NUMBER,
                    "targetType", FieldType.TEXT,
                    "targetId", FieldType.NUMBER_OR_NULL);
        }

        /**
         * 명령 단계는 이 Snapshot의 필드 이름으로 쓸 수 있는 필드를 정한다(`AI05-013`).
         *
         * <p>그래서 등록 대상도 빈 자리를 갖춘 틀을 주고, 자리는 {@code position}으로 담는다.
         * {@code id}만 주면 쓸 수 있는 필드가 없어 등록이 막히고, {@code position}이 없으면
         * 순서 변경이 막힌다.
         */
        @Override
        public ObjectNode snapshot(String id) {
            ObjectNode state = objectMapper.createObjectNode();
            if (NEW_ID.equals(id)) {
                state.put("id", NEW_ID);
                state.putNull("name");
                state.putNull("path");
                state.putNull("parentId");
                state.putNull("position");
                state.put("targetType", "NONE");
                state.putNull("targetId");
                return state;
            }
            MenuView view = cmsService.menu(numericId(id, "MENU"));
            state.put("id", view.id());
            state.put("name", view.name());
            state.put("path", view.path());
            state.put("parentId", view.parentId());
            state.put("displayOrder", view.displayOrder());
            state.put("position", ordinalOf(view));
            state.put("targetType", view.targetType());
            state.put("targetId", view.targetId());
            return state;
        }

        /** 형제 안에서 몇 번째인지. 모델은 이 서수로만 자리를 말한다. */
        private int ordinalOf(MenuView view) {
            List<MenuView> group = siblings(view.parentId());
            for (int index = 0; index < group.size(); index++) {
                if (group.get(index).id() == view.id()) {
                    return index + 1;
                }
            }
            return group.size() + 1;
        }

        /**
         * 모델이 지어낼 수 있는 번호를 서버가 미리 준다.
         *
         * <p>메뉴는 상위 메뉴와 연결 대상 둘 다 번호가 필요하다. 자리는 서수로만 주고
         * 실제 {@code displayOrder}는 넣지 않는다.
         */
        @Override
        public ObjectNode promptContext(String id) {
            ObjectNode context = objectMapper.createObjectNode();
            ArrayNode menus = context.putArray("menus");
            for (MenuView view : cmsService.menus()) {
                ObjectNode entry = menus.addObject();
                entry.put("id", view.id());
                entry.put("name", view.name());
                entry.put("path", view.path());
                entry.put("parentId", view.parentId());
                entry.put("position", position(view));
            }
            ArrayNode contents = context.putArray("contents");
            cmsService.contents().forEach(view -> contents.addObject()
                    .put("id", view.id())
                    .put("name", view.title()));
            ArrayNode boards = context.putArray("boards");
            cmsService.boards().forEach(view -> boards.addObject()
                    .put("id", view.id())
                    .put("name", view.name()));
            return context;
        }

        @Override
        public CmsRequests.MenuRequest merged(Command command, String id) {
            JsonNode fields = command.fields();
            if (command.deletes()) {
                requireRemovableWithinLimit(cmsService.menu(numericId(id, "MENU")));
                return null;
            }
            if (command.creates()) {
                Long parentId = number(fields, "parentId", null);
                return new CmsRequests.MenuRequest(
                        text(fields, "name", null),
                        text(fields, "path", null),
                        parentId,
                        placedOrder(null, parentId, fields),
                        text(fields, "targetType", "NONE"),
                        number(fields, "targetId", null));
            }
            MenuView view = cmsService.menu(numericId(id, "MENU"));
            Long parentId = number(fields, "parentId", view.parentId());
            return new CmsRequests.MenuRequest(
                    text(fields, "name", view.name()),
                    text(fields, "path", view.path()),
                    parentId,
                    order(view, parentId, fields),
                    text(fields, "targetType", view.targetType()),
                    number(fields, "targetId", view.targetId()));
        }

        /**
         * 대상 한 행을 먼저 쓰고, 자리를 명시했을 때만 형제를 다시 번호 매긴다.
         *
         * <p>바깥 승인 반영이 이미 하나의 트랜잭션이라 대상과 형제가 함께 기록된다.
         */
        @Override
        public JsonNode save(Command command, String id, CmsRequests.MenuRequest request) {
            if (command.deletes()) {
                long menuId = numericId(id, "MENU");
                ObjectNode removed = snapshot(id);
                cmsService.deleteMenu(menuId);
                return removed;
            }
            MenuView saved = command.creates()
                    ? cmsService.createMenu(
                            request.name(), request.path(), request.parentId(),
                            request.displayOrder(), request.targetType(), request.targetId())
                    : cmsService.updateMenu(
                            numericId(id, "MENU"), request.name(), request.path(),
                            request.parentId(), request.displayOrder(),
                            request.targetType(), request.targetId());
            if (!command.fields().has("position")) {
                return objectMapper.valueToTree(saved);
            }
            renumber(
                    request.parentId(),
                    placement(saved.id(), request.parentId(),
                            command.fields().path("position").asInt()),
                    saved.id());
            return objectMapper.valueToTree(cmsService.menu(saved.id()));
        }

        /** 자리를 명시하지 않으면 기존 번호를 그대로 둔다. 순서는 자리를 말했을 때만 움직인다. */
        private int order(MenuView view, Long parentId, JsonNode fields) {
            if (fields.has("position")) {
                return placedOrder(view.id(), parentId, fields);
            }
            return fields.has("displayOrder")
                    ? fields.path("displayOrder").asInt() : view.displayOrder();
        }

        /** 자리를 말하지 않은 등록은 맨 뒤에 붙는다. 대메뉴는 다음 구역, 하위는 형제 다음 번호다. */
        private int placedOrder(Long movingId, Long parentId, JsonNode fields) {
            if (!fields.has("position")) {
                List<MenuView> siblings = siblings(parentId);
                return orderAt(parentId, siblings.size());
            }
            List<Long> placement = placement(movingId, parentId, fields.path("position").asInt());
            return orderAt(parentId, placement.indexOf(movingId));
        }

        /**
         * 대상을 뺀 형제 목록에 대상을 원하는 자리로 끼운다.
         *
         * <p>{@code position}은 1부터 세는 서수다. 범위를 벗어난 값은 양 끝으로 자른다.
         */
        private List<Long> placement(Long movingId, Long parentId, int position) {
            List<Long> ids = new ArrayList<>();
            for (MenuView view : siblings(parentId)) {
                if (movingId == null || view.id() != movingId) {
                    ids.add(view.id());
                }
            }
            ids.add(Math.max(0, Math.min(position - 1, ids.size())), movingId);
            return ids;
        }

        /**
         * 형제 전체를 규칙대로 다시 번호 매긴다. 대상은 방금 저장했으므로 다시 쓰지 않는다.
         *
         * <p>대메뉴 구역이 움직이면 그 하위도 구역을 따라 옮긴다. 같은 부모의 하위만 건드리므로
         * 다른 가지는 그대로 남는다.
         */
        private void renumber(Long parentId, List<Long> placement, long savedId) {
            for (int index = 0; index < placement.size(); index++) {
                long menuId = placement.get(index);
                int order = orderAt(parentId, index);
                if (menuId != savedId) {
                    move(cmsService.menu(menuId), order);
                }
                if (parentId == null) {
                    renumberChildren(menuId, order);
                }
            }
        }

        /** 대메뉴가 옮겨가면 하위도 새 구역 안으로 따라간다. 하위는 스스로 바뀐 게 아니다. */
        private void renumberChildren(long parentId, int parentOrder) {
            List<MenuView> children = siblings(parentId);
            for (int index = 0; index < children.size(); index++) {
                move(children.get(index), parentOrder + index + 1);
            }
        }

        private void move(MenuView view, int order) {
            if (view.displayOrder() != order) {
                cmsService.updateMenu(view.id(), view.name(), view.path(), view.parentId(),
                        order, view.targetType(), view.targetId());
            }
        }

        /**
         * 대메뉴는 {@code 10} 간격으로 자기 구역을 갖고 하위는 그 구역 안에 들어간다.
         *
         * <p>관리 화면의 메뉴 목록이 전역 {@code displayOrder} 순서를 그대로 쓰므로 이 관례를 지켜야
         * 하위가 엉뚱한 대메뉴 밑으로 흩어지지 않는다.
         */
        private int orderAt(Long parentId, int index) {
            if (parentId == null) {
                return (index + 1) * TOP_LEVEL_STEP;
            }
            return cmsService.menu(parentId).displayOrder() + index + 1;
        }

        /** 형제는 같은 {@code parentId}다. 대메뉴 형제를 읽는 조회가 없어 정렬된 전체를 거른다. */
        private List<MenuView> siblings(Long parentId) {
            return cmsService.menus().stream()
                    .filter(view -> parentId == null
                            ? view.parentId() == null : parentId.equals(view.parentId()))
                    .toList();
        }

        private int position(MenuView view) {
            return siblings(view.parentId()).stream()
                    .map(MenuView::id)
                    .toList()
                    .indexOf(view.id()) + 1;
        }

        /** 하위가 함께 사라지므로 규모가 커질 수 있다. 한 명령이 넘길 수 없는 선을 둔다. */
        private void requireRemovableWithinLimit(MenuView view) {
            int removed = 1 + siblings(view.id()).size();
            if (removed > MAX_COMMAND_ROWS) {
                throw invalidCommand("Deleting this menu removes " + removed
                        + " menus at once. Split the request into smaller ones.");
            }
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
        public CmsRequests.BoardRequest merged(Command command, String id) {
            BoardView view = cmsService.board(numericId(id, "BOARD"));
            JsonNode fields = command.fields();
            return new CmsRequests.BoardRequest(
                    text(fields, "name", view.name()),
                    text(fields, "description", view.description()));
        }

        @Override
        public JsonNode save(Command command, String id, CmsRequests.BoardRequest request) {
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
        public CmsRequests.ArticleRequest merged(Command command, String id) {
            ContentView view = cmsService.content(numericId(id, "CONTENT"));
            JsonNode fields = command.fields();
            String body = text(fields, "body", view.body());
            requireSupportedMarkdown(body);
            return new CmsRequests.ArticleRequest(text(fields, "title", view.title()), body);
        }

        @Override
        public JsonNode save(Command command, String id, CmsRequests.ArticleRequest request) {
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
        public CmsRequests.TemplateRequest merged(Command command, String id) {
            TemplateView view = template(id);
            JsonNode fields = command.fields();
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
        public JsonNode save(Command command, String id, CmsRequests.TemplateRequest request) {
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

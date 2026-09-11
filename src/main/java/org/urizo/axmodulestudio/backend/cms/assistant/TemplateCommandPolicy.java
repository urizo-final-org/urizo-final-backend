package org.urizo.axmodulestudio.backend.cms.assistant;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.urizo.axmodulestudio.backend.cms.dto.CmsResponses.TemplateView;
import org.urizo.axmodulestudio.backend.cms.dto.TemplateHeroImage;
import org.urizo.axmodulestudio.backend.cms.service.CmsService;

/** 선택 템플릿의 기존 편집 항목만 여는 규칙. CMS Job·Snapshot 실행 구조는 소유하지 않는다. */
final class TemplateCommandPolicy {
    static final Set<String> FIELDS = Set.of("layout", "primaryColor", "headerText", "footerText",
            "heroImages", "heroTitle", "heroSubtitle", "heroButtonLabel", "heroButtonUrl");
    private static final Set<String> IMAGE_FIELDS = Set.of("url", "title", "description");
    private static final Set<String> LAYOUTS = Set.of("CLASSIC", "MINIMAL", "BOLD");
    private static final Pattern ATTACHMENTS = Pattern.compile(
            "\\n\\n\\[이 요청에 첨부한 사진 주소: ([^\\r\\n\\]]+)\\]\\z");
    private static final Pattern IMAGE_URL = Pattern.compile("/api/site/images/([1-9][0-9]*)");

    private TemplateCommandPolicy() { }

    static List<String> attachments(String requestText) {
        var match = ATTACHMENTS.matcher(requestText == null ? "" : requestText);
        if (!match.find()) return List.of();
        List<String> urls = List.of(match.group(1).split(", ", -1));
        if (urls.size() > 5 || urls.stream().anyMatch(url -> !IMAGE_URL.matcher(url).matches())) {
            throw invalid("첨부 사진 주소가 올바르지 않습니다.");
        }
        return urls.stream().distinct().toList();
    }

    static Set<String> imageUrls(TemplateView current, String requestText, CmsService cms) {
        Set<String> urls = new LinkedHashSet<>();
        current.heroImages().forEach(image -> urls.add(image.url()));
        for (String url : attachments(requestText)) {
            var match = IMAGE_URL.matcher(url);
            match.matches();
            try {
                if (!cms.contentImageExists(Long.parseLong(match.group(1)))) {
                    throw invalid("첨부한 사진을 찾을 수 없습니다. 사진을 다시 첨부해 주세요.");
                }
            } catch (NumberFormatException failure) {
                throw invalid("첨부 사진 주소가 올바르지 않습니다.");
            }
            urls.add(url);
        }
        return urls;
    }

    static Set<String> buttonPaths(CmsService cms) {
        Set<String> paths = new LinkedHashSet<>(List.of("/", "/search", "/sitemap"));
        cms.menus().stream().map(menu -> menu.path())
                .filter(TemplateCommandPolicy::internalPath).forEach(paths::add);
        return paths;
    }

    private static boolean internalPath(String path) {
        return path != null && path.startsWith("/") && !path.startsWith("//")
                && !path.contains("\\") && path.chars().noneMatch(Character::isISOControl);
    }

    static boolean imageList(JsonNode value) {
        if (!value.isArray() || value.size() > 5) return false;
        for (JsonNode image : value) {
            Set<String> names = new LinkedHashSet<>();
            image.fieldNames().forEachRemaining(names::add);
            if (!image.isObject() || !names.equals(IMAGE_FIELDS)
                    || !image.path("url").isTextual() || image.path("url").asText().isBlank()
                    || image.path("url").asText().length() > 500
                    || !image.path("title").isTextual() || image.path("title").asText().length() > 120
                    || !image.path("description").isTextual()
                    || image.path("description").asText().length() > 240) return false;
        }
        return true;
    }

    static void normalize(ObjectNode fields) {
        // 저장 서비스와 동일하게 공백·빈 선택 항목을 정규화한 값을 Preview/hash에 담는다.
        for (String name : FIELDS) {
            JsonNode value = fields.get(name);
            if (value == null) continue;
            if (value.isTextual()) fields.put(name, name.equals("primaryColor")
                    ? value.asText().trim().toUpperCase(java.util.Locale.ROOT) : value.asText().trim());
            else if (value.isNull() && Set.of("headerText", "footerText", "heroSubtitle",
                    "heroButtonLabel", "heroButtonUrl").contains(name)) fields.put(name, "");
            else if (name.equals("heroImages") && value.isArray()) {
                for (JsonNode image : value) {
                    if (image instanceof ObjectNode object) {
                        for (String key : IMAGE_FIELDS) {
                            if (object.path(key).isTextual()) object.put(key, object.path(key).asText().trim());
                        }
                    }
                }
            }
        }
    }

    static List<TemplateHeroImage> images(JsonNode fields, TemplateView current,
            String requestText, CmsService cms) {
        if (!fields.has("heroImages")) return current.heroImages();
        Set<String> allowed = imageUrls(current, requestText, cms);
        List<TemplateHeroImage> images = new ArrayList<>();
        for (JsonNode image : fields.path("heroImages")) {
            String url = image.path("url").asText();
            if (!allowed.contains(url) || !safeImageUrl(url)) {
                throw invalid("현재 템플릿의 사진이나 이 요청에 첨부한 사진만 사용할 수 있습니다.");
            }
            images.add(new TemplateHeroImage(url, image.path("title").asText(),
                    image.path("description").asText()));
        }
        return List.copyOf(images);
    }

    private static boolean safeImageUrl(String value) {
        if (IMAGE_URL.matcher(value).matches()) return true;
        // Existing seeded templates also use bundled /images/... assets.
        if (internalPath(value) && !value.contains("..")) return true;
        try {
            URI uri = URI.create(value);
            return ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) && uri.getHost() != null
                    && uri.getUserInfo() == null;
        } catch (IllegalArgumentException failure) {
            return false;
        }
    }

    static void validate(JsonNode fields, CmsService cms) {
        if (fields.has("layout") && !LAYOUTS.contains(fields.path("layout").asText())) {
            throw invalid("기존 레이아웃 CLASSIC, MINIMAL, BOLD만 선택할 수 있습니다.");
        }
        if (fields.has("heroButtonUrl") && !fields.path("heroButtonUrl").asText().isEmpty()
                && !buttonPaths(cms).contains(fields.path("heroButtonUrl").asText())) {
            throw invalid("메인 버튼은 기존 메뉴 또는 검색·사이트맵 경로로만 연결할 수 있습니다.");
        }
    }

    static ObjectNode fieldSchema(ObjectMapper mapper) {
        ObjectNode fields = mapper.createObjectNode().put("type", "object")
                .put("additionalProperties", false).put("minProperties", 1);
        ObjectNode properties = fields.putObject("properties");
        properties.putObject("layout").put("type", "string").putArray("enum")
                .add("CLASSIC").add("MINIMAL").add("BOLD");
        properties.putObject("primaryColor").put("type", "string").put("pattern", "^#[0-9A-Fa-f]{6}$");
        properties.putObject("heroTitle").put("type", "string").put("minLength", 1).put("maxLength", 160);
        properties.putObject("headerText").put("type", "string").put("maxLength", 200);
        properties.putObject("footerText").put("type", "string").put("maxLength", 200);
        properties.putObject("heroSubtitle").put("type", "string").put("maxLength", 300);
        properties.putObject("heroButtonLabel").put("type", "string").put("maxLength", 60);
        properties.putObject("heroButtonUrl").put("type", "string").put("maxLength", 180);
        ObjectNode item = properties.putObject("heroImages").put("type", "array")
                .put("maxItems", 5).putObject("items").put("type", "object").put("additionalProperties", false);
        item.putArray("required").add("url").add("title").add("description");
        ObjectNode image = item.putObject("properties");
        image.putObject("url").put("type", "string").put("minLength", 1).put("maxLength", 500);
        image.putObject("title").put("type", "string").put("maxLength", 120);
        image.putObject("description").put("type", "string").put("maxLength", 240);
        return fields;
    }

    private static NaturalCmsException invalid(String message) {
        return new NaturalCmsException("CMS_COMMAND_INVALID", message, HttpStatus.UNPROCESSABLE_ENTITY);
    }
}

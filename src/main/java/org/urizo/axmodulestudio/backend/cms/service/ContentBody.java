package org.urizo.axmodulestudio.backend.cms.service;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 컨텐츠 본문의 형식. 저장 형식은 Tiptap Document(JSON) 하나다.
 *
 * <p>DB에는 마크다운으로 쓰던 옛 본문이 남아 있다. 그것을 한 번에 바꾸는 대신 <b>읽는 입구에서만</b>
 * 변환한다. {@link #toDocument}를 조회 경로 한 곳에 두면 관리자 화면·공개 사이트·자연어 Snapshot이
 * 모두 JSON을 받는다. 어느 경로로든 저장되는 순간 DB도 JSON이 되므로 변환은 저절로 끝난다.
 *
 * <p>저장 입구는 {@link #problem}으로 막는다. 허용한 부품만 통과하며, 이것이 마크다운 3문법
 * 제한을 대신하는 가드레일이다. 사람이 쓰든 모델이 쓰든 같은 검사를 탄다.
 *
 * <p>게시물도 같은 문서를 사용하며 옛 마크다운은 조회 시 호환 변환한다.
 */
public final class ContentBody {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 사용자 화면 렌더러가 그릴 수 있는 부품. 목록에 없으면 저장을 막는다. */
    private static final Set<String> NODES = Set.of(
            "doc", "paragraph", "heading", "bulletList", "orderedList", "listItem",
            "text", "image", "hardBreak", "blockquote", "horizontalRule");

    /**
     * 글자에 붙는 서식. 속성이 없어 이름만 확인하면 된다.
     *
     * <p>{@code strike} {@code underline}은 편집기 도입 때 함께 껐다가 열었다. 보통 편집기에
     * 있는 서식인데 허용 목록에 없어 쓸 수 없었다.
     *
     * <p>인라인 코드는 열지 않는다. 이 사이트의 컨텐츠에 명령어나 함수 이름을 쓸 일이 없다.
     */
    private static final Set<String> MARKS = Set.of(
            "bold", "italic", "link", "strike", "underline", "textStyle", "highlight");

    /**
     * 본문에 쓸 수 있는 색.
     *
     * <p>**고정 목록으로 둔다.** 색을 자유롭게 받으면 임의의 CSS 값이 본문에 들어오고, 그것이
     * 안전한지 판단할 방법이 없다. 허용 목록을 두는 의미가 사라진다.
     *
     * <p>Frontend `features/site/contentPalette.ts`가 같은 값을 들고 있다. 저장소가 갈려 있어
     * 한 벌을 공유할 수 없으므로 한쪽을 고치면 다른 쪽도 고친다.
     */
    private static final Set<String> TEXT_COLORS = Set.of(
            "#6b7d84", "#c0392b", "#1d6fb8", "#2a7d55", "#c1701a");

    private static final Set<String> HIGHLIGHT_COLORS = Set.of(
            "#fff3a3", "#d5f2dd", "#d8ecfb", "#fbdce8", "#e6ebed");

    /** 사이트 본문이 그리는 제목 단계. 문서 제목은 별도 필드라 1단계는 쓰지 않는다. */
    private static final Set<Integer> HEADING_LEVELS = Set.of(2, 3);

    /** 이미지는 우리가 저장한 것만 가리킨다. 외부 주소를 열면 방문자 접속 기록이 새어 나간다. */
    private static final Pattern IMAGE_SRC = Pattern.compile("^/api/site/images/[1-9][0-9]*$");

    /** 링크는 외부 주소와 사이트 안 경로만 받는다. {@code javascript:} 같은 것을 막는다. */
    private static final Pattern LINK_HREF = Pattern.compile("^(?:https?://|/)[^\\s]*$");

    private static final Pattern BOLD = Pattern.compile("\\*\\*([^*]+)\\*\\*");

    private ContentBody() {
    }

    /** Call only after problem() has accepted the document. */
    public static Set<Long> imageIds(String body) {
        Set<Long> ids = new java.util.HashSet<>();
        collectImages(parse(body), ids);
        return ids;
    }

    private static void collectImages(JsonNode node, Set<Long> ids) {
        if (node == null) return;
        if ("image".equals(node.path("type").asText())) {
            String src = node.path("attrs").path("src").asText();
            try {
                ids.add(Long.parseLong(src.substring(src.lastIndexOf('/') + 1)));
            } catch (NumberFormatException failure) {
                throw CmsServiceException.invalidRequest("이미지 ID가 올바르지 않습니다.");
            }
        }
        for (JsonNode child : node.path("content")) collectImages(child, ids);
    }

    /**
     * 읽어 온 본문을 Document JSON으로 준다. 이미 JSON이면 그대로 돌려준다.
     *
     * <p>옛 마크다운은 제목({@code ## }), 강조({@code **문구**}), 목록({@code - }) 셋만 쓰였다.
     * 그때 저장 전에 그 셋만 통과시켰기 때문에 다른 문법이 섞여 있을 수 없다.
     */
    public static String toDocument(String body) {
        if (body == null || body.isBlank()) {
            return body;
        }
        JsonNode document = parse(body);
        if (document != null) {
            return document.toString();
        }
        return MAPPER.valueToTree(fromMarkdown(body)).toString();
    }

    /**
     * 저장할 본문을 표준 형태로 되돌린다. 문서가 아니면 받은 값을 그대로 준다.
     *
     * <p>모델이 문서를 <b>한 번 더 escape</b> 해서 보내는 일이 있다. 같은 요청인데 어떤 때는
     * 제대로, 어떤 때는 {@code {\"type\":\"doc\"...}} 처럼 보낸다. 지시문으로 없앨 수 있는
     * 종류가 아니라 여기서 되돌린다. 되돌린 뒤에도 허용 부품 검사는 그대로 탄다.
     */
    public static String normalize(String body) {
        JsonNode document = parse(body);
        return document == null ? body : document.toString();
    }

    /**
     * 저장하려는 본문에 허용하지 않은 것이 있으면 사유를, 없으면 {@code null}을 준다.
     *
     * <p>사유 문자열은 호출하는 쪽이 자기 예외로 감싼다. 폼 경로와 자연어 경로가 예외 종류만
     * 다르고 검사는 같아야 하기 때문이다.
     */
    public static String problem(String body) {
        if (body == null || body.isBlank()) {
            return "본문이 비어 있습니다.";
        }
        JsonNode document = parse(body);
        if (document == null) {
            return "본문은 편집기가 만든 문서 형식이어야 합니다.";
        }
        if (!document.path("content").isArray() || document.path("content").isEmpty()) {
            return "본문이 비어 있습니다.";
        }
        return walk(document);
    }

    /**
     * 본문을 문서로 읽는다. 문서가 아니면 {@code null}이다.
     *
     * <p>한 번 더 escape 된 값도 받아준다. 그 경우 문자열 리터럴로 한 번 풀면 원래 문서가 된다.
     */
    private static JsonNode parse(String body) {
        if (body == null || body.isBlank() || !body.stripLeading().startsWith("{")) {
            return null;
        }
        JsonNode document = readDocument(body);
        return document != null ? document : readDocument(unescapeOnce(body));
    }

    private static JsonNode readDocument(String body) {
        if (body == null) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(body);
            return node.isObject() && "doc".equals(node.path("type").asText()) ? node : null;
        }
        catch (JsonProcessingException failure) {
            return null;
        }
    }

    /** 통째로 따옴표에 넣어 문자열 리터럴로 읽는다. 그러면 escape가 한 겹 풀린다. */
    private static String unescapeOnce(String body) {
        try {
            return MAPPER.readValue("\"" + body + "\"", String.class);
        }
        catch (JsonProcessingException failure) {
            return null;
        }
    }

    /**
     * 색 속성이 팔레트 안에 있는지.
     *
     * @param optional 색이 없어도 되는지. {@code textStyle}은 색 말고 다른 속성만 담고 올 수 있다.
     */
    private static boolean allowedColor(JsonNode mark, Set<String> palette, boolean optional) {
        JsonNode color = mark.path("attrs").path("color");
        if (color.isMissingNode() || color.isNull()) {
            return optional;
        }
        return color.isTextual() && palette.contains(color.textValue());
    }

    /** 부품을 하나씩 내려가며 확인한다. 처음 걸린 사유 하나만 돌려준다. */
    private static String walk(JsonNode node) {
        String type = node.path("type").asText();
        if (!NODES.contains(type)) {
            return "본문에 쓸 수 없는 " + (type.isEmpty() ? "항목" : type) + "이(가) 있습니다.";
        }
        if ("heading".equals(type)
                && !HEADING_LEVELS.contains(node.path("attrs").path("level").asInt())) {
            return "제목은 2단계와 3단계만 쓸 수 있습니다.";
        }
        if ("image".equals(type)
                && !IMAGE_SRC.matcher(node.path("attrs").path("src").asText()).matches()) {
            return "이미지는 이 CMS에 올린 것만 넣을 수 있습니다.";
        }
        for (JsonNode mark : node.path("marks")) {
            String markType = mark.path("type").asText();
            if (!MARKS.contains(markType)) {
                return "본문에 쓸 수 없는 서식이 있습니다.";
            }
            if ("link".equals(markType)
                    && !LINK_HREF.matcher(mark.path("attrs").path("href").asText()).matches()) {
                return "링크 주소가 올바르지 않습니다.";
            }
            // 색은 정해 둔 것만 받는다. `textStyle`은 색 없이 올 수도 있어 있을 때만 본다.
            if ("textStyle".equals(markType) && !allowedColor(mark, TEXT_COLORS, true)) {
                return "글자색은 고를 수 있는 색만 쓸 수 있습니다.";
            }
            if ("highlight".equals(markType) && !allowedColor(mark, HIGHLIGHT_COLORS, false)) {
                return "형광펜은 고를 수 있는 색만 쓸 수 있습니다.";
            }
        }
        for (JsonNode child : node.path("content")) {
            String problem = walk(child);
            if (problem != null) {
                return problem;
            }
        }
        return null;
    }

    /** 옛 마크다운 세 문법을 문서로 옮긴다. 연속된 목록 줄은 하나로 묶는다. */
    private static ObjectNode fromMarkdown(String body) {
        ObjectNode document = MAPPER.createObjectNode();
        document.put("type", "doc");
        ArrayNode content = document.putArray("content");
        ArrayNode list = null;
        for (String line : body.split("\n", -1)) {
            String text = line.strip();
            if (text.isEmpty()) {
                list = null;
                continue;
            }
            if (text.startsWith("- ")) {
                if (list == null) {
                    ObjectNode bulletList = content.addObject();
                    bulletList.put("type", "bulletList");
                    list = bulletList.putArray("content");
                }
                ObjectNode item = list.addObject();
                item.put("type", "listItem");
                item.putArray("content").add(paragraph(text.substring(2)));
                continue;
            }
            list = null;
            if (text.startsWith("## ")) {
                ObjectNode heading = content.addObject();
                heading.put("type", "heading");
                heading.putObject("attrs").put("level", 2);
                heading.set("content", inline(text.substring(3)));
                continue;
            }
            content.add(paragraph(text));
        }
        if (content.isEmpty()) {
            content.add(paragraph(""));
        }
        return document;
    }

    private static ObjectNode paragraph(String text) {
        ObjectNode paragraph = MAPPER.createObjectNode();
        paragraph.put("type", "paragraph");
        paragraph.set("content", inline(text));
        return paragraph;
    }

    /** {@code **문구**}만 굵게로 옮긴다. 나머지는 글자 그대로 둔다. */
    private static ArrayNode inline(String text) {
        ArrayNode parts = MAPPER.createArrayNode();
        Matcher matcher = BOLD.matcher(text);
        int cursor = 0;
        while (matcher.find()) {
            addText(parts, text.substring(cursor, matcher.start()), false);
            addText(parts, matcher.group(1), true);
            cursor = matcher.end();
        }
        addText(parts, text.substring(cursor), false);
        return parts;
    }

    private static void addText(ArrayNode parts, String text, boolean bold) {
        if (text.isEmpty()) {
            return;
        }
        ObjectNode node = parts.addObject();
        node.put("type", "text");
        node.put("text", text);
        if (bold) {
            ArrayNode marks = node.putArray("marks");
            marks.addObject().put("type", "bold");
        }
    }
}

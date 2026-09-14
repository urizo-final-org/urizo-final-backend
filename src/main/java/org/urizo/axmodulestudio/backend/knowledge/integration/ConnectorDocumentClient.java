package org.urizo.axmodulestudio.backend.knowledge.integration;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.urizo.axmodulestudio.backend.knowledge.dto.ProductApiContract;

/**
 * 등록된 커넥터 {@code config_json}으로 실제 원천 API를 조회한다.
 *
 * <p>지금까지 COLLECT는 jar 안 관광 표본을 읽었고 커넥터 설정은 저장만 됐다. 이 클래스가 그
 * 설정을 실제 호출로 바꾼다. 새 의존성을 넣지 않고 {@link EmbeddingClient}와 같은
 * {@link HttpClient} + Jackson 조합을 쓴다.
 *
 * <p>등록되는 JSONPath는 배열 인덱싱이나 조건식이 없는 단순 dot-path뿐이라
 * ({@code $.response.body.items.item}) JSONPath 라이브러리를 추가하지 않고 직접 해석한다.
 * 계약 검증({@code ConnectorStore})이 {@code $} 시작만 허용하므로 입력 형태도 그 범위로 좁다.
 */
@Component
@Profile("local-full")
public class ConnectorDocumentClient {

    /**
     * 원천이 주는 시각 표기가 제각각이라 순서대로 시도한다. 커넥터 계약에 형식 필드가 없고,
     * 형식 하나를 강제하면 도메인마다 계약을 고쳐야 한다.
     */
    private static final List<DateTimeFormatter> TIMESTAMP_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("uuuuMMddHHmmss"));

    private static final ZoneId SOURCE_ZONE = ZoneId.of("Asia/Seoul");
    private static final int MAX_PAGES = 200;

    private static final Pattern LINE_BREAK = Pattern.compile("<br\\s*/?>", Pattern.CASE_INSENSITIVE);
    private static final Pattern CLOSING_BLOCK =
            Pattern.compile("</(?:p|div)\\s*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final Pattern ENTITY = Pattern.compile("&(#x?[0-9a-fA-F]+|[a-zA-Z]+);");
    private static final Pattern BLANK_RUN = Pattern.compile("\n{3,}");
    private static final Pattern SPACE_RUN = Pattern.compile("[ \t\u00a0]{2,}");

    private static final Map<String, String> NAMED_ENTITIES = Map.of(
            "nbsp", "\u00a0", "amp", "&", "lt", "<", "gt", ">",
            "quot", "\"", "apos", "'", "middot", "·", "hellip", "…");

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    ConnectorDocumentClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * 설정된 페이지 크기로 {@code maxDocuments}에 도달할 때까지 조회한다.
     *
     * @param apiKey {@code authentication.secretRef}가 가리키는 실제 값. 호출자가 해석해 넘긴다.
     */
    public List<ProductApiContract.PreviewDocument> fetch(
            JsonNode config, String apiKey, int maxDocuments) {
        JsonNode pagination = config.path("pagination");
        JsonNode response = config.path("response");
        JsonNode mapping = config.path("documentMapping");
        int startPage = pagination.path("startPage").asInt(1);
        int pageSize = pagination.path("pageSize").asInt(100);

        List<ProductApiContract.PreviewDocument> documents = new ArrayList<>();
        for (int page = startPage; page < startPage + MAX_PAGES; page++) {
            JsonNode payload = request(config, apiKey, page, pageSize);
            requireSuccess(payload, response);
            List<JsonNode> items = items(payload, response.path("itemsPath").asText());
            if (items.isEmpty()) {
                break;
            }
            for (JsonNode item : items) {
                documents.add(document(item, mapping));
                if (documents.size() >= maxDocuments) {
                    return List.copyOf(documents);
                }
            }
            if (items.size() < pageSize) {
                break;
            }
        }
        return List.copyOf(documents);
    }

    /**
     * 문서 하나의 상세를 가져와 매핑된 한 필드만 읽는다(AXMS-AI02-023 W3).
     *
     * <p>목록 API가 개요를 주지 않는 원천이 있다. 이 호출은 문서 수만큼 늘어나므로 호출자가
     * 예산과 재사용을 책임진다 — 여기서는 한 건만 가져온다.
     *
     * @return 정제한 본문. 상세가 없거나 값이 비면 {@code null}.
     */
    public String detail(JsonNode config, String apiKey, String documentId) {
        JsonNode detail = config.path("documentMapping").path("detail");
        JsonNode response = config.path("response");
        // 목록용 파라미터를 그대로 실으면 안 된다 — 상세 경로가 목록 전용 파라미터를 거부하는
        // 원천이 있다(TourAPI detailCommon2는 arrange에 INVALID_REQUEST_PARAMETER를 낸다).
        // 어느 것이 공통이고 어느 것이 목록 전용인지는 설정만 알 수 있으므로 따로 선언한다.
        JsonNode payload = request(config, apiKey, detail.path("endpoint").asText(),
                detail.path("parameters"), 1, 1,
                Map.of(detail.path("parameter").asText(), documentId));
        requireSuccess(payload, response);
        List<JsonNode> items = items(payload, response.path("itemsPath").asText());
        if (items.isEmpty()) {
            return null;
        }
        String text = clean(at(items.get(0), detail.path("path").asText()).asText(""));
        return text.isBlank() ? null : text;
    }

    private JsonNode request(JsonNode config, String apiKey, int page, int pageSize) {
        return request(config, apiKey, config.path("endpoint").asText(),
                config.path("requestParameters"), page, pageSize, Map.of());
    }

    private JsonNode request(
            JsonNode config, String apiKey, String endpoint, JsonNode requestParameters,
            int page, int pageSize, Map<String, String> extra) {
        JsonNode authentication = config.path("authentication");
        JsonNode pagination = config.path("pagination");
        boolean queryKey = "QUERY".equals(authentication.path("location").asText());

        Map<String, String> parameters = new LinkedHashMap<>();
        if (queryKey) {
            parameters.put(authentication.path("name").asText(), apiKey);
        }
        for (JsonNode parameter : requestParameters) {
            JsonNode defaultValue = parameter.path("defaultValue");
            if (!defaultValue.isMissingNode() && !defaultValue.isNull()) {
                parameters.put(parameter.path("name").asText(), defaultValue.asText());
            }
        }
        parameters.putAll(extra);
        parameters.put(pagination.path("pageParameter").asText(), Integer.toString(page));
        parameters.put(pagination.path("pageSizeParameter").asText(), Integer.toString(pageSize));

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(uri(config, endpoint, parameters))
                .timeout(Duration.ofSeconds(60))
                .GET();
        if (!queryKey) {
            builder.header(authentication.path("name").asText(), apiKey);
        }

        HttpResponse<String> httpResponse;
        try {
            httpResponse = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }
        catch (IOException failure) {
            throw new IllegalStateException("Connector source request failed.", failure);
        }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Connector source request was interrupted.", failure);
        }
        if (httpResponse.statusCode() != 200) {
            throw new IllegalStateException(
                    "Connector source returned HTTP " + httpResponse.statusCode() + ".");
        }
        try {
            return objectMapper.readTree(httpResponse.body());
        }
        catch (IOException failure) {
            // 공공 API는 키·경로 오류를 200 + XML 오류 문서로 돌려주기도 한다. 본문은 키를
            // 포함할 수 있으므로 예외 메시지에 싣지 않는다.
            throw new IllegalStateException("Connector source did not return JSON.", failure);
        }
    }

    private static URI uri(JsonNode config, String endpoint, Map<String, String> parameters) {
        StringBuilder query = new StringBuilder();
        parameters.forEach((name, value) -> {
            query.append(query.isEmpty() ? '?' : '&')
                    .append(URLEncoder.encode(name, StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
        });
        return URI.create(config.path("baseUrl").asText() + endpoint + query);
    }

    /**
     * {@code successCodePath}가 있으면 그 값이 {@code successValues}에 있어야 한다. 공공 API는
     * 인증 실패나 한도 초과도 HTTP 200으로 돌려주므로 상태 코드만으로는 판별되지 않는다.
     */
    private static void requireSuccess(JsonNode payload, JsonNode response) {
        JsonNode codePath = response.path("successCodePath");
        if (!codePath.isTextual()) {
            return;
        }
        String actual = at(payload, codePath.asText()).asText();
        for (JsonNode expected : response.path("successValues")) {
            if (expected.asText().equals(actual)) {
                return;
            }
        }
        throw new IllegalStateException("Connector source reported result code " + actual + ".");
    }

    /** dot-path를 따라 내려간다. 없으면 missing 노드를 돌려준다. */
    static JsonNode at(JsonNode root, String path) {
        JsonNode node = root;
        for (String field : path.split("\\.")) {
            if (field.isEmpty() || "$".equals(field)) {
                continue;
            }
            node = node.path(field);
        }
        return node;
    }

    /**
     * 결과가 1건이면 배열이 아니라 객체로 오는 원천이 있다. 그때 0건으로 읽으면 마지막
     * 페이지가 조용히 사라지므로 단일 객체도 1건으로 취급한다.
     */
    static List<JsonNode> items(JsonNode root, String itemsPath) {
        JsonNode node = at(root, itemsPath);
        if (node.isArray()) {
            List<JsonNode> items = new ArrayList<>(node.size());
            node.forEach(items::add);
            return items;
        }
        return node.isObject() ? List.of(node) : List.of();
    }

    /**
     * 원본에 문서 URL이 없는 원천(TourAPI 축제 목록 등)을 위한 합성 주소.
     * {@code source_document.source_url}이 NOT NULL이라 비워 둘 수 없고, 픽스처 로더가
     * 같은 이유로 합성 주소를 쓰는 선례를 따른다(AI02-023).
     */
    private static final String SYNTHETIC_SOURCE_URL = "https://source.invalid/documents/";

    static ProductApiContract.PreviewDocument document(JsonNode item, JsonNode mapping) {
        String documentId = required(item, mapping, "documentId");
        String sourceUrl = optional(item, mapping, "sourceUrl");
        if (sourceUrl != null && !sourceUrl.startsWith("https://")) {
            // source_document가 ^https:// CHECK를 걸고 있다. 여기서 막지 않으면 적재 시점에
            // 원인을 알기 어려운 제약 위반으로 나타난다.
            throw new IllegalStateException(
                    "Connector document " + documentId + " has a non-HTTPS source URL.");
        }
        String category = optional(item, mapping, "category");
        String updatedAt = optional(item, mapping, "sourceUpdatedAt");
        return new ProductApiContract.PreviewDocument(
                documentId,
                required(item, mapping, "title").strip(),
                content(item, mapping, documentId),
                category == null || category.isBlank() ? List.of() : List.of(category.strip()),
                URI.create(sourceUrl == null ? SYNTHETIC_SOURCE_URL + documentId : sourceUrl),
                updatedAt == null ? null : timestamp(updatedAt, documentId),
                optional(item, mapping, "imageUrl"));
    }

    /**
     * 색인 본문을 만든다. 정제한 본문 뒤에 {@code documentMapping.metadata}의 값을
     * {@code [라벨] 값} 줄로 덧붙인다.
     *
     * <p>이 조립이 COLLECT에 있는 이유는 여기서만 원본 항목을 볼 수 있기 때문이다.
     * {@code source_document}에 메타데이터 칸이 없어 NORMALIZE 단계에 도달할 때는 신청방법·
     * 문의처가 이미 사라진 뒤다. 관광이 {@code [홈페이지]} 줄을 본문에 남긴 것과 같은 방식이다.
     *
     * <p>라벨은 metadata 매핑의 <b>키</b>를 그대로 쓴다. 도메인마다 다른 라벨을 코드가 알 필요가
     * 없고, 커넥터 설정이 순서까지 정한다.
     */
    private static String content(JsonNode item, JsonNode mapping, String documentId) {
        StringBuilder body = new StringBuilder(clean(required(item, mapping, "content")));
        if (body.isEmpty()) {
            throw new IllegalStateException(
                    "Connector document " + documentId + " has no content after cleaning.");
        }
        categoryLine(item, mapping).ifPresent(line -> body.append('\n').append(line));
        Iterator<Map.Entry<String, JsonNode>> entries = mapping.path("metadata").fields();
        while (entries.hasNext()) {
            Map.Entry<String, JsonNode> entry = entries.next();
            if (!entry.getValue().isTextual()) {
                continue;
            }
            JsonNode value = at(item, entry.getValue().asText());
            if (value.isMissingNode() || value.isNull()) {
                continue;
            }
            String cleaned = clean(value.asText());
            if (!cleaned.isEmpty()) {
                body.append('\n').append('[').append(entry.getKey()).append("] ").append(cleaned);
            }
        }
        return body.toString();
    }

    /**
     * 원천 본문을 색인 가능한 평문으로 바꾼다.
     *
     * <p>태그 제거와 엔티티 복원의 <b>순서가 계약이다.</b> 원문에는 {@code &lt;2026 예술산업보증&gt;}
     * 처럼 꺾쇠를 인용부호로 쓴 제목이 있다(실측 500건 중 6건). 엔티티를 먼저 복원하면
     * {@code <2026 예술산업보증>}이 되어 태그 제거가 본문을 지운다.
     *
     * <p>시험지 코퍼스를 만드는 {@code api-test/sme/build_sme_corpus.py}의 {@code clean()}과
     * 같은 결과를 내야 한다. 어긋나면 시험지가 "본문에 있는 사실"로 만든 질문을 색인이 답하지 못한다.
     */
    static String clean(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String text = LINE_BREAK.matcher(raw).replaceAll("\n");
        text = CLOSING_BLOCK.matcher(text).replaceAll("\n");
        text = TAG.matcher(text).replaceAll("");
        text = unescape(text).replace('\u00a0', ' ');
        text = Arrays.stream(text.split("\r\n|\r|\n", -1))
                .map(String::strip)
                .collect(Collectors.joining("\n"));
        text = BLANK_RUN.matcher(text).replaceAll("\n\n");
        return SPACE_RUN.matcher(text).replaceAll(" ").strip();
    }

    /** 한 번만 훑는다. {@code &amp;lt;}가 {@code <}까지 풀리면 안 되기 때문이다. */
    private static String unescape(String text) {
        Matcher matcher = ENTITY.matcher(text);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            String replacement = name.startsWith("#")
                    ? codePoint(name)
                    : NAMED_ENTITIES.get(name);
            matcher.appendReplacement(result,
                    replacement == null ? Matcher.quoteReplacement(matcher.group())
                            : Matcher.quoteReplacement(replacement));
        }
        return matcher.appendTail(result).toString();
    }

    /**
     * 숫자 참조를 문자로 바꾼다. 풀 수 없으면 {@code null}을 돌려 명명 엔티티와 같이 원문을
     * 그대로 남긴다. 원천 응답 한 건의 이상한 참조로 수집 Job 전체가 죽으면 안 된다.
     *
     * <p>{@code &#abc;}는 정규식의 16진수 문자 집합에 걸리지만 접두사 {@code x}가 없어
     * 10진수로 파싱된다. {@code &#99999999999;}는 int 범위를 넘는다.
     */
    private static String codePoint(String name) {
        try {
            int code = name.charAt(1) == 'x' || name.charAt(1) == 'X'
                    ? Integer.parseInt(name.substring(2), 16)
                    : Integer.parseInt(name.substring(1));
            return Character.isValidCodePoint(code) ? Character.toString(code) : null;
        }
        catch (NumberFormatException unparsable) {
            return null;
        }
    }

    /**
     * 분류 코드를 사람이 쓰는 말로 바꿔 본문에 한 줄 남긴다(AXMS-AI02-023).
     *
     * <p>원천이 분류를 코드로만 주면 "숙박"·"쇼핑" 같은 검색어가 본문 어디에도 없어 그 질문이
     * 통째로 검색되지 않는다(관광 실측: 픽스처 코퍼스 83건 → 코드만 실은 코퍼스 9건). 코드표는
     * 도메인마다 다르므로 코드가 아니라 커넥터 설정이 갖는다.
     *
     * <p>가장 긴 접두가 이긴다. {@code AC}와 {@code AC03}이 함께 있으면 더 구체적인 쪽을 쓴다.
     */
    private static Optional<String> categoryLine(JsonNode item, JsonNode mapping) {
        JsonNode line = mapping.path("categoryLine");
        String category = optional(item, mapping, "category");
        if (!line.isObject() || category == null) {
            return Optional.empty();
        }
        String matched = null;
        String label = null;
        Iterator<Map.Entry<String, JsonNode>> codes = line.path("codes").fields();
        while (codes.hasNext()) {
            Map.Entry<String, JsonNode> code = codes.next();
            if (category.startsWith(code.getKey())
                    && (matched == null || code.getKey().length() > matched.length())) {
                matched = code.getKey();
                label = code.getValue().asText();
            }
        }
        return label == null || label.isBlank()
                ? Optional.empty()
                : Optional.of("[" + line.path("label").asText("분류") + "] " + label);
    }

    private static String required(JsonNode item, JsonNode mapping, String field) {
        String value = optional(item, mapping, field);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Connector document is missing mapped '" + field + "'.");
        }
        return value;
    }

    /**
     * 매핑된 값. <b>빈 문자열은 없는 것으로 본다</b> — 공공 API는 값이 없는 칸을 {@code null}이
     * 아니라 {@code ""}로 돌려준다. 빈 문자열을 그대로 흘리면 적재 시점에 CHECK 위반이나
     * 파싱 실패로 나타나고, 그때는 어느 칸이 비었는지 알아보기 어렵다.
     */
    private static String optional(JsonNode item, JsonNode mapping, String field) {
        JsonNode path = mapping.path(field);
        if (!path.isTextual()) {
            return null;
        }
        JsonNode value = at(item, path.asText());
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text.isBlank() ? null : text;
    }

    static Instant timestamp(String raw, String documentId) {
        String value = raw.trim();
        try {
            return Instant.parse(value);
        }
        catch (DateTimeParseException ignored) {
            // 아래 지역 시각 형식들을 순서대로 시도한다.
        }
        for (DateTimeFormatter format : TIMESTAMP_FORMATS) {
            try {
                return LocalDateTime.parse(value, format).atZone(SOURCE_ZONE).toInstant();
            }
            catch (DateTimeParseException ignored) {
                continue;
            }
        }
        throw new IllegalStateException(
                "Connector document " + documentId + " has an unreadable source timestamp.");
    }
}

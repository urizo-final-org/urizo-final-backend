package org.urizo.axmodulestudio.backend.knowledge.repository;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.urizo.axmodulestudio.backend.knowledge.dto.ProductApiContract;
import org.urizo.axmodulestudio.backend.knowledge.exception.ProductApiException;
import org.urizo.axmodulestudio.backend.knowledge.integration.ConnectorDocumentClient;
import org.urizo.axmodulestudio.backend.knowledge.integration.ConnectorSecretResolver;
import org.urizo.axmodulestudio.backend.knowledge.integration.ConnectorSourcePolicy;
import org.urizo.axmodulestudio.backend.knowledge.integration.DeterministicConnectorFixture;

@Repository
@Profile("local-full")
public class ConnectorStore {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final ProjectStore projects;
    private final ConnectorSourcePolicy sources;
    private final ConnectorDocumentClient documents;
    private final ConnectorSecretResolver secrets;

    ConnectorStore(
            JdbcTemplate productJdbcTemplate,
            ObjectMapper objectMapper,
            Clock clock,
            ProjectStore projects,
            ConnectorSourcePolicy sources,
            ConnectorDocumentClient documents,
            ConnectorSecretResolver secrets) {
        this.jdbc = productJdbcTemplate;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.projects = projects;
        this.sources = sources;
        this.documents = documents;
        this.secrets = secrets;
    }

    public ProductApiContract.ConnectorResponse createConnector(
            UUID projectId,
            UUID traceId,
            ProductApiContract.CreateConnectorRequest request) {
        projects.requireProject(projectId);
        validateConnector(request);
        UUID connectorId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        Instant now = Instant.now(clock);
        ObjectNode config = objectMapper.createObjectNode();
        config.put("baseUrl", request.baseUrl().toString());
        config.put("endpoint", request.endpoint());
        config.put("method", request.method());
        config.set("authentication", request.authentication());
        config.set("requestParameters", objectMapper.valueToTree(request.requestParameters()));
        config.set("response", request.response());
        config.set("pagination", request.pagination());
        config.set("documentMapping", request.documentMapping());
        String configJson = encode(config);
        String configDigest = sha256(configJson);
        try {
            jdbc.update(
                    "INSERT INTO app.connector "
                            + "(connector_id, project_id, name, status, created_at, updated_at) "
                            + "VALUES (?, ?, ?, 'DRAFT', ?, ?)",
                    connectorId, projectId, request.name(), Timestamp.from(now), Timestamp.from(now));
        }
        catch (DuplicateKeyException taken) {
            // uq_connector_project_name. 사전 SELECT는 경합에 지므로 제약이 판정하게 두고
            // 번역만 한다. 번역하지 않으면 "제품 저장소를 쓸 수 없음"(503)으로 나가 이름이
            // 겹쳤다는 사실이 화면에서 사라진다.
            throw conflict("CONNECTOR_NAME_TAKEN",
                    "A connector with this name already exists in the project.");
        }
        jdbc.update(
                "INSERT INTO app.connector_version "
                        + "(connector_version_id, connector_id, version_number, status, "
                        + "config_json, config_digest, created_at) "
                        + "VALUES (?, ?, 1, 'DRAFT', ?::jsonb, ?, ?)",
                versionId, connectorId, configJson, configDigest, Timestamp.from(now));
        return new ProductApiContract.ConnectorResponse(
                version(), traceId, projectId, connectorId, versionId,
                request.name(), "DRAFT", configDigest, now);
    }

    public ProductApiContract.ConnectorResponse getConnector(UUID connectorId, UUID traceId) {
        return one(jdbc.query(connectorSelect() + " WHERE c.connector_id = ?",
                (rs, row) -> connector(rs, traceId), connectorId),
                "CONNECTOR_NOT_FOUND", "Connector not found.");
    }

    public List<ProductApiContract.ConnectorResponse> listConnectors(
            UUID projectId, UUID traceId) {
        projects.requireProject(projectId);
        return jdbc.query(
                connectorSelect() + " WHERE c.project_id = ? ORDER BY c.created_at, c.connector_id",
                (rs, row) -> connector(rs, traceId), projectId);
    }

    public ConnectorConfig connectorConfig(UUID connectorId) {
        return one(jdbc.query(
                "SELECT c.connector_id, c.project_id, cv.connector_version_id, cv.status, "
                        + "cv.config_json::text, cv.config_digest "
                        + "FROM app.connector c JOIN app.connector_version cv ON cv.connector_id = c.connector_id "
                        + "WHERE c.connector_id = ? "
                        + "ORDER BY (cv.connector_version_id = c.active_version_id) DESC, "
                        + "cv.version_number DESC LIMIT 1",
                (rs, row) -> new ConnectorConfig(
                        rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class), rs.getString(4),
                        decodeTree(rs.getString(5)), rs.getString(6)), connectorId),
                "CONNECTOR_NOT_FOUND", "Connector not found.");
    }

    public ProductApiContract.ConnectorPreviewResponse previewConnector(
            UUID connectorId,
            UUID traceId,
            ProductApiContract.ConnectorPreviewRequest request) {
        ConnectorConfig config = connectorConfig(connectorId);
        String baseUrl = config.config().path("baseUrl").asText();
        sources.require(baseUrl);

        List<ProductApiContract.PreviewDocument> preview;
        int totalCount;
        boolean truncated;
        if (sources.isFixture(baseUrl)) {
            preview = DeterministicConnectorFixture.documents(request.maxItems());
            totalCount = DeterministicConnectorFixture.totalCount();
            truncated = preview.size() < totalCount;
        }
        else {
            String key = secrets.resolve(
                    config.config().path("authentication").path("secretRef").asText());
            preview = documents.fetch(config.config(), key, request.maxItems());
            // 원천 전체 건수를 확인하려면 페이지를 끝까지 돌아야 한다. 미리보기가 할 일이
            // 아니므로 받아온 만큼만 보고하고, 요청 수를 채웠으면 더 있다고 표시한다.
            totalCount = preview.size();
            truncated = preview.size() >= request.maxItems();
        }

        Instant now = Instant.now(clock);
        jdbc.update("UPDATE app.connector_version SET previewed_at = ? "
                        + "WHERE connector_version_id = ?",
                Timestamp.from(now), config.connectorVersionId());
        return new ProductApiContract.ConnectorPreviewResponse(
                version(), traceId, connectorId, preview.size(),
                totalCount, preview, truncated, now);
    }

    public ProductApiContract.ConnectorResponse activateConnectorVersion(
            UUID connectorId, UUID versionId, UUID traceId) {
        ConnectorVersionRow row = one(jdbc.query(
                "SELECT c.project_id, c.name, c.active_version_id, cv.status, "
                        + "cv.config_digest, cv.created_at "
                        + "FROM app.connector c JOIN app.connector_version cv "
                        + "ON cv.connector_id = c.connector_id "
                        + "WHERE c.connector_id = ? AND cv.connector_version_id = ? FOR UPDATE OF c, cv",
                (rs, index) -> new ConnectorVersionRow(
                        rs.getObject(1, UUID.class), rs.getString(2),
                        rs.getObject(3, UUID.class), rs.getString(4),
                        rs.getString(5), instant(rs, 6)), connectorId, versionId),
                "CONNECTOR_VERSION_NOT_FOUND", "Connector version not found.");
        if (!"DRAFT".equals(row.status()) && !"ACTIVE".equals(row.status())) {
            throw conflict(
                    "CONNECTOR_VERSION_NOT_ACTIVATABLE", "Connector version cannot be activated.");
        }
        Instant now = Instant.now(clock);
        if (row.activeVersionId() != null && !row.activeVersionId().equals(versionId)) {
            jdbc.update("UPDATE app.connector_version SET status = 'ARCHIVED' "
                    + "WHERE connector_version_id = ?", row.activeVersionId());
        }
        jdbc.update(
                "UPDATE app.connector_version SET status = 'ACTIVE', "
                        + "activated_at = COALESCE(activated_at, ?) WHERE connector_version_id = ?",
                Timestamp.from(now), versionId);
        jdbc.update(
                "UPDATE app.connector SET status = 'ACTIVE', active_version_id = ?, updated_at = ? "
                        + "WHERE connector_id = ?",
                versionId, Timestamp.from(now), connectorId);
        return new ProductApiContract.ConnectorResponse(
                version(), traceId, row.projectId(), connectorId, versionId,
                row.name(), "ACTIVE", row.configDigest(), row.createdAt());
    }

    /** Job 생성 시점에도 원천 경계를 다시 확인한다. 등록 이후 허용 목록이 좁아졌을 수 있다. */
    public void requireSupportedSource(String baseUrl) {
        sources.require(baseUrl);
    }

    private ProductApiContract.ConnectorResponse connector(ResultSet rs, UUID traceId)
            throws SQLException {
        return new ProductApiContract.ConnectorResponse(
                version(), traceId, rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class), rs.getString(4), rs.getString(5),
                rs.getString(6), instant(rs, 7));
    }

    private String connectorSelect() {
        return "SELECT c.project_id, c.connector_id, cv.connector_version_id, c.name, "
                + "cv.status, cv.config_digest, cv.created_at FROM app.connector c "
                + "JOIN app.connector_version cv ON cv.connector_version_id = COALESCE("
                + "c.active_version_id, (SELECT cv2.connector_version_id FROM app.connector_version cv2 "
                + "WHERE cv2.connector_id = c.connector_id ORDER BY cv2.version_number DESC LIMIT 1))";
    }

    private void validateConnector(ProductApiContract.CreateConnectorRequest request) {
        if (!"GET".equals(request.method())) {
            throw validation("Only deterministic GET connectors are supported in the local profile.");
        }
        URI base = request.baseUrl().normalize();
        if (!"https".equalsIgnoreCase(base.getScheme()) || base.getHost() == null
                || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null
                || base.toString().length() > 500) {
            throw validation("Connector baseUrl is not a canonical HTTPS origin or base path.");
        }
        if (!sources.allows(base.toString())) {
            throw validation("The connector source host is not on the allowed list.");
        }
        if (request.endpoint().contains("..") || request.endpoint().startsWith("//")) {
            throw validation("Connector endpoint is not an origin-relative safe path.");
        }
        validateAuthentication(request.authentication());
        validateRequestParameters(request.requestParameters());
        validateResponseMapping(request.response());
        validatePagination(request.pagination());
        validateDocumentMapping(request.documentMapping());
        validateSecretRef(request.authentication().path("secretRef"), base.toString());
    }

    /**
     * 원천 종류와 인증 참조 방식을 묶는다.
     *
     * <p>픽스처 커넥터는 호출이 없으므로 값이 필요 없고, 실제 원천은 파일 Secret이 있어야 한다.
     * 짝이 어긋나면 등록은 되고 수집에서 실패하므로 여기서 막는다.
     */
    private void validateSecretRef(JsonNode secretRef, String baseUrl) {
        if (!secretRef.isTextual()) {
            throw validation("Connector authentication secretRef is invalid.");
        }
        String value = secretRef.asText();
        if (sources.isFixture(baseUrl)) {
            if (!value.startsWith("fixture://")) {
                throw validation("The local fixture connector requires a fixture:// reference.");
            }
            return;
        }
        if (!value.startsWith("cms-secret://")) {
            throw validation("A live connector source requires a cms-secret:// reference.");
        }
        if (!ConnectorSecretResolver.wellFormed(value)) {
            throw validation("Connector secret reference name is invalid.");
        }
    }

    private static void validateAuthentication(JsonNode value) {
        requireFields(value,
                Set.of("type", "location", "name", "secretRef"), Set.of(), "authentication");
        if (!"API_KEY".equals(text(value, "type", 1, 120))
                || !Set.of("QUERY", "HEADER").contains(text(value, "location", 1, 120))) {
            throw validation("Connector authentication type or location is invalid.");
        }
        text(value, "name", 1, 120);
        String secretRef = text(value, "secretRef", 1, 500);
        if (!secretRef.matches("^[A-Za-z][A-Za-z0-9+.-]*://.*$")) {
            throw validation("Connector authentication secretRef is invalid.");
        }
    }

    private static void validateRequestParameters(List<JsonNode> parameters) {
        if (parameters.size() > 100) {
            throw validation("Connector requestParameters exceeds the contract limit.");
        }
        for (JsonNode parameter : parameters) {
            requireFields(parameter, Set.of("name", "type", "required"),
                    Set.of("description", "defaultValue"), "request parameter");
            text(parameter, "name", 1, 120);
            if (!Set.of("STRING", "INTEGER", "NUMBER", "BOOLEAN")
                    .contains(text(parameter, "type", 1, 120))
                    || !parameter.path("required").isBoolean()) {
                throw validation("Connector request parameter type is invalid.");
            }
            if (parameter.has("description")) {
                text(parameter, "description", 0, 500);
            }
            if (parameter.has("defaultValue")) {
                JsonNode defaultValue = parameter.get("defaultValue");
                if (!(defaultValue.isTextual()
                        || defaultValue.isNumber()
                        || defaultValue.isBoolean())) {
                    throw validation("Connector request parameter defaultValue is invalid.");
                }
            }
        }
    }

    private static void validateResponseMapping(JsonNode value) {
        requireFields(value, Set.of("itemsPath"),
                Set.of("successCodePath", "successValues", "totalCountPath"), "response mapping");
        jsonPath(value, "itemsPath");
        if (value.has("successCodePath")) {
            jsonPath(value, "successCodePath");
        }
        if (value.has("totalCountPath")) {
            jsonPath(value, "totalCountPath");
        }
        if (value.has("successValues")) {
            JsonNode successValues = value.get("successValues");
            if (!successValues.isArray() || successValues.isEmpty()) {
                throw validation("Connector response successValues is invalid.");
            }
            Set<JsonNode> unique = new HashSet<>();
            for (JsonNode item : successValues) {
                if (!(item.isTextual() || item.isIntegralNumber()) || !unique.add(item)) {
                    throw validation("Connector response successValues is invalid.");
                }
            }
        }
    }

    private static void validatePagination(JsonNode value) {
        requireFields(value,
                Set.of("type", "pageParameter", "pageSizeParameter", "startPage", "pageSize"),
                Set.of(), "pagination");
        if (!"PAGE".equals(text(value, "type", 1, 120))) {
            throw validation("Connector pagination type is invalid.");
        }
        text(value, "pageParameter", 1, 120);
        text(value, "pageSizeParameter", 1, 120);
        JsonNode startPage = value.path("startPage");
        JsonNode pageSize = value.path("pageSize");
        if (!startPage.isIntegralNumber() || !startPage.canConvertToInt()
                || startPage.intValue() < 0 || !pageSize.isIntegralNumber()
                || !pageSize.canConvertToInt() || pageSize.intValue() < 1
                || pageSize.intValue() > 1_000) {
            throw validation("Connector pagination bounds are invalid.");
        }
    }

    private static void validateDocumentMapping(JsonNode value) {
        // imageUrl은 수집기가 이미 읽는 매핑인데 여기서 빠져 있었다. 그래서 실수집 커넥터로는
        // 사진 있는 문서를 만들 수 없었다(픽스처 코퍼스만 405/500장을 갖고 있던 이유).
        Set<String> paths = Set.of("documentId", "title", "content", "category",
                "sourceUpdatedAt", "sourceUrl", "imageUrl");
        requireFields(value, Set.of("documentId", "title", "content"),
                Set.of("category", "sourceUpdatedAt", "sourceUrl", "imageUrl", "metadata",
                        "detail", "categoryLine"),
                "document mapping");
        for (String field : paths) {
            if (value.has(field)) {
                jsonPath(value, field);
            }
        }
        if (value.has("metadata")) {
            JsonNode metadata = value.get("metadata");
            if (!metadata.isObject()) {
                throw validation("Connector document metadata mapping is invalid.");
            }
            metadata.fields().forEachRemaining(entry -> requireJsonPath(entry.getValue()));
        }
        if (value.has("detail")) {
            validateDetail(value.get("detail"));
        }
        if (value.has("categoryLine")) {
            validateCategoryLine(value.get("categoryLine"));
        }
    }

    /** 분류 코드를 사람이 쓰는 말로 바꿔 본문에 남기는 표. 코드표는 도메인마다 다르다. */
    private static void validateCategoryLine(JsonNode value) {
        requireFields(value, Set.of("codes"), Set.of("label"), "document category line");
        if (value.has("label")) {
            text(value, "label", 1, 40);
        }
        JsonNode codes = value.get("codes");
        if (!codes.isObject() || codes.isEmpty() || codes.size() > 100) {
            throw validation("Connector category line codes are invalid.");
        }
        codes.fields().forEachRemaining(entry -> {
            if (entry.getKey().isBlank() || entry.getKey().length() > 40
                    || !entry.getValue().isTextual()
                    || entry.getValue().textValue().isBlank()
                    || entry.getValue().textValue().length() > 120) {
                throw validation("Connector category line codes are invalid.");
            }
        });
    }

    /**
     * 문서별 2차 조회 설정. 목록 API가 주지 않는 본문(개요 등)을 문서 하나씩 가져온다.
     *
     * <p>문서 수만큼 호출이 늘어나는 유일한 설정이라 형식을 여기서 못 박는다. 경로가
     * {@code endpoint}로 제한되는 것은 목록과 같은 원천·같은 인증만 쓰게 하기 위해서다.
     */
    private static void validateDetail(JsonNode value) {
        requireFields(value, Set.of("endpoint", "parameter", "path"),
                Set.of("label", "parameters"), "document detail");
        String endpoint = text(value, "endpoint", 1, 500);
        if (!endpoint.startsWith("/") || endpoint.startsWith("//") || endpoint.contains("..")) {
            throw validation("Connector detail endpoint is not an origin-relative safe path.");
        }
        text(value, "parameter", 1, 120);
        requireJsonPath(value.get("path"));
        if (value.has("label")) {
            text(value, "label", 1, 40);
        }
        if (value.has("parameters")) {
            JsonNode parameters = value.get("parameters");
            if (!parameters.isArray()) {
                throw validation("Connector detail parameters must be an array.");
            }
            List<JsonNode> items = new ArrayList<>();
            parameters.forEach(items::add);
            validateRequestParameters(items);
        }
    }

    private static void requireFields(
            JsonNode value, Set<String> required, Set<String> optional, String label) {
        if (!value.isObject()) {
            throw validation("Connector " + label + " must be an object.");
        }
        Set<String> actual = new HashSet<>();
        value.fieldNames().forEachRemaining(actual::add);
        Set<String> allowed = new HashSet<>(required);
        allowed.addAll(optional);
        if (!actual.containsAll(required) || !allowed.containsAll(actual)) {
            throw validation("Connector " + label + " fields are invalid.");
        }
    }

    private static String text(JsonNode value, String field, int minimum, int maximum) {
        JsonNode node = value.get(field);
        if (node == null || !node.isTextual() || node.textValue().length() < minimum
                || node.textValue().length() > maximum) {
            throw validation("Connector " + field + " is invalid.");
        }
        return node.textValue();
    }

    private static void jsonPath(JsonNode value, String field) {
        requireJsonPath(value.get(field));
    }

    private static void requireJsonPath(JsonNode value) {
        if (value == null || !value.isTextual() || value.textValue().isEmpty()
                || value.textValue().length() > 500 || !value.textValue().startsWith("$")) {
            throw validation("Connector JSONPath mapping is invalid.");
        }
    }

    private String encode(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        }
        catch (JsonProcessingException failure) {
            throw new IllegalStateException("Cannot encode product state.", failure);
        }
    }

    private JsonNode decodeTree(String json) {
        try {
            return objectMapper.readTree(json);
        }
        catch (JsonProcessingException failure) {
            throw new IllegalStateException("Stored connector configuration is invalid.", failure);
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + java.util.HexFormat.of().formatHex(digest);
        }
        catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable.", failure);
        }
    }

    private static Instant instant(ResultSet rs, int column) throws SQLException {
        return rs.getTimestamp(column).toInstant();
    }

    private static <T> T one(List<T> values, String code, String message) {
        if (values.isEmpty()) {
            throw new ProductApiException(code, message, HttpStatus.NOT_FOUND);
        }
        return values.get(0);
    }

    private static ProductApiException validation(String message) {
        return new ProductApiException("CONTRACT_VALIDATION_FAILED", message, HttpStatus.BAD_REQUEST);
    }

    private static ProductApiException conflict(String code, String message) {
        return new ProductApiException(code, message, HttpStatus.CONFLICT);
    }

    private static String version() {
        return ProductApiContract.SCHEMA_VERSION;
    }

    public record ConnectorConfig(
            UUID connectorId,
            UUID projectId,
            UUID connectorVersionId,
            String status,
            JsonNode config,
            String configDigest) {
    }

    private record ConnectorVersionRow(
            UUID projectId,
            String name,
            UUID activeVersionId,
            String status,
            String configDigest,
            Instant createdAt) {
    }
}

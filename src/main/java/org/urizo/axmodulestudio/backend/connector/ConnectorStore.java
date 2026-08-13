package org.urizo.axmodulestudio.backend.connector;

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
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.urizo.axmodulestudio.backend.product.ProductApiContract;
import org.urizo.axmodulestudio.backend.product.ProductApiException;
import org.urizo.axmodulestudio.backend.project.ProjectStore;

@Repository
@Profile("local-full")
public class ConnectorStore {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final ProjectStore projects;

    ConnectorStore(
            JdbcTemplate productJdbcTemplate,
            ObjectMapper objectMapper,
            Clock clock,
            ProjectStore projects) {
        this.jdbc = productJdbcTemplate;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.projects = projects;
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
        jdbc.update(
                "INSERT INTO app.connector "
                        + "(connector_id, project_id, name, status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'DRAFT', ?, ?)",
                connectorId, projectId, request.name(), Timestamp.from(now), Timestamp.from(now));
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
        requireFixture(config.config().path("baseUrl").asText());
        List<ProductApiContract.PreviewDocument> documents =
                DeterministicConnectorFixture.documents(request.maxItems());
        Instant now = Instant.now(clock);
        jdbc.update("UPDATE app.connector_version SET previewed_at = ? "
                        + "WHERE connector_version_id = ?",
                Timestamp.from(now), config.connectorVersionId());
        return new ProductApiContract.ConnectorPreviewResponse(
                version(), traceId, connectorId, documents.size(),
                DeterministicConnectorFixture.totalCount(), documents,
                documents.size() < DeterministicConnectorFixture.totalCount(), now);
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

    public void requireFixture(String baseUrl) {
        if (!DeterministicConnectorFixture.supports(baseUrl)) {
            throw new ProductApiException(
                    "CONNECTOR_FIXTURE_REQUIRED",
                    "Only the deterministic local connector adapter is enabled.",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
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

    private static void validateConnector(ProductApiContract.CreateConnectorRequest request) {
        if (!"GET".equals(request.method())) {
            throw validation("Only deterministic GET connectors are supported in the local profile.");
        }
        URI base = request.baseUrl().normalize();
        if (!"https".equalsIgnoreCase(base.getScheme()) || base.getHost() == null
                || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null
                || base.toString().length() > 500) {
            throw validation("Connector baseUrl is not a canonical HTTPS origin or base path.");
        }
        if (!DeterministicConnectorFixture.supports(base.toString())) {
            throw validation("The local profile accepts only HTTPS fixture.invalid connector origins.");
        }
        if (request.endpoint().contains("..") || request.endpoint().startsWith("//")) {
            throw validation("Connector endpoint is not an origin-relative safe path.");
        }
        validateAuthentication(request.authentication());
        validateRequestParameters(request.requestParameters());
        validateResponseMapping(request.response());
        validatePagination(request.pagination());
        validateDocumentMapping(request.documentMapping());
        JsonNode secretRef = request.authentication().path("secretRef");
        if (!secretRef.isTextual() || !secretRef.asText().startsWith("fixture://")) {
            throw validation("The local fixture connector requires a non-secret fixture:// reference.");
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
        Set<String> paths = Set.of(
                "documentId", "title", "content", "category", "sourceUpdatedAt", "sourceUrl");
        requireFields(value, Set.of("documentId", "title", "content"),
                Set.of("category", "sourceUpdatedAt", "sourceUrl", "metadata"),
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

package org.urizo.axmodulestudio.backend.integration.ai.mcp;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public final class McpPlatformClient {

    private static final Pattern WORKSPACE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$");
    private static final Pattern GIT_HEAD = Pattern.compile("^(?:[0-9a-f]{40}|[0-9a-f]{64})$");
    private static final Pattern DIFF_DIGEST = Pattern.compile("^sha256:[0-9a-f]{64}$");

    private static final String SERVER_INFO_META_KEY = "io.modelcontextprotocol/serverInfo";
    private static final String PROTOCOL_VERSION_META_KEY = "io.modelcontextprotocol/protocolVersion";
    private static final String CLIENT_INFO_META_KEY = "io.modelcontextprotocol/clientInfo";
    private static final String CLIENT_CAPABILITIES_META_KEY = "io.modelcontextprotocol/clientCapabilities";

    private final URI endpoint;
    private final String authorization;
    private final Duration requestTimeout;
    private final int maxResponseBytes;
    private final McpHttpTransport transport;
    private final ObjectMapper objectMapper;
    private final AtomicLong requestSequence = new AtomicLong();

    McpPlatformClient(
            URI endpoint,
            String serviceToken,
            Duration requestTimeout,
            int maxResponseBytes,
            McpHttpTransport transport,
            ObjectMapper objectMapper) {
        this.endpoint = endpoint;
        this.authorization = "Bearer " + serviceToken;
        this.requestTimeout = requestTimeout;
        this.maxResponseBytes = maxResponseBytes;
        this.transport = transport;
        this.objectMapper = objectMapper;
    }

    public McpPlatformContract.Snapshot probeCatalog() {
        JsonNode discovery = invoke("server/discover");
        validateDiscovery(discovery);
        Set<String> exposedTools = validateCatalog(invoke("tools/list"));
        return new McpPlatformContract.Snapshot(
                McpPlatformContract.PROTOCOL_VERSION,
                McpPlatformContract.SERVER_NAME,
                exposedTools);
    }

    public JsonNode callTool(String toolName, JsonNode arguments) {
        if (arguments == null
                || !arguments.isObject()) {
            throw new McpPlatformException("MCP tool call contract was rejected.");
        }
        if (McpPlatformContract.codingToolNames().contains(toolName)) {
            validateCodingArguments(toolName, arguments);
        }
        else if (McpPlatformContract.cmsToolNames().contains(toolName)) {
            validateCmsArguments(toolName, arguments);
        }
        else {
            throw new McpPlatformException("MCP tool call contract was rejected.");
        }
        JsonNode result = invoke("tools/call", toolName, arguments);
        if (!result.path("isError").isBoolean()
                || !result.path("structuredContent").isObject()) {
            throw new McpPlatformException("MCP coding tool result contract was rejected.");
        }
        return result;
    }

    private static void validateCmsArguments(String toolName, JsonNode arguments) {
        Set<String> expected = switch (toolName) {
            case "resolve_cms_target" -> Set.of("resource", "currentState");
            case "validate_cms_command", "create_cms_preview" ->
                    Set.of("resource", "command", "currentState");
            case "discard_cms_preview" -> Set.of("previewId", "previewHash");
            case "revalidate_cms_preview", "apply_cms_preview" -> Set.of(
                    "previewId", "previewHash", "resource", "command", "currentState");
            default -> throw new McpPlatformException(
                    "MCP Natural CMS tool call contract was rejected.");
        };
        Set<String> actual = new HashSet<>();
        arguments.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) {
            throw new McpPlatformException("MCP Natural CMS tool call contract was rejected.");
        }
        if (expected.contains("resource")) {
            JsonNode resource = arguments.path("resource");
            Set<String> resourceFields = new HashSet<>();
            resource.fieldNames().forEachRemaining(resourceFields::add);
            if (!resource.isObject()
                    || !resourceFields.equals(Set.of("type", "id"))
                    || !resource.path("type").isTextual()
                    || !resource.path("id").isTextual()
                    || !arguments.path("currentState").isObject()
                    || arguments.path("currentState").isEmpty()) {
                throw new McpPlatformException(
                        "MCP Natural CMS tool call contract was rejected.");
            }
        }
        if (expected.contains("command") && !arguments.path("command").isObject()) {
            throw new McpPlatformException("MCP Natural CMS tool call contract was rejected.");
        }
        if (expected.contains("previewId")) {
            try {
                UUID.fromString(arguments.path("previewId").asText());
            }
            catch (IllegalArgumentException failure) {
                throw new McpPlatformException(
                        "MCP Natural CMS tool call contract was rejected.");
            }
            if (!matches(arguments, "previewHash", DIFF_DIGEST)) {
                throw new McpPlatformException(
                        "MCP Natural CMS tool call contract was rejected.");
            }
        }
    }

    private static void validateCodingArguments(String toolName, JsonNode arguments) {
        Set<String> required = switch (toolName) {
            case "read_file" -> Set.of("workspace", "expectedHead", "path");
            case "search_code" -> Set.of("workspace", "expectedHead", "query");
            case "read_diff" -> Set.of("workspace", "expectedHead");
            case "apply_patch" -> Set.of(
                    "workspace", "expectedHead", "expectedDiffDigest", "patch");
            case "run_check" -> Set.of(
                    "workspace", "expectedHead", "expectedDiffDigest", "profile");
            case "check_package_allowlist", "scan_changed_files" -> Set.of(
                    "workspace", "expectedHead", "expectedDiffDigest");
            default -> throw new McpPlatformException(
                    "MCP coding tool call contract was rejected.");
        };
        Set<String> allowed = "search_code".equals(toolName)
                ? Set.of("workspace", "expectedHead", "query", "scope")
                : required;
        Set<String> actual = new HashSet<>();
        arguments.fieldNames().forEachRemaining(actual::add);
        if (!actual.containsAll(required) || !allowed.containsAll(actual)
                || !matches(arguments, "workspace", WORKSPACE)
                || !matches(arguments, "expectedHead", GIT_HEAD)
                || (required.contains("expectedDiffDigest")
                    && !matches(arguments, "expectedDiffDigest", DIFF_DIGEST))) {
            throw new McpPlatformException("MCP coding tool call contract was rejected.");
        }
        for (String field : actual) {
            if (!Set.of("workspace", "expectedHead", "expectedDiffDigest").contains(field)
                    && (!arguments.path(field).isTextual()
                        || arguments.path(field).asText().isBlank())) {
                throw new McpPlatformException("MCP coding tool call contract was rejected.");
            }
        }
    }

    private static boolean matches(JsonNode arguments, String field, Pattern pattern) {
        return arguments.path(field).isTextual()
                && pattern.matcher(arguments.path(field).asText()).matches();
    }

    private JsonNode invoke(String method) {
        return invoke(method, null, null);
    }

    private JsonNode invoke(String method, String toolName, JsonNode arguments) {
        String requestId = "axms-mcp-" + requestSequence.incrementAndGet();
        ObjectNode request = objectMapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", requestId);
        request.put("method", method);
        ObjectNode params = request.putObject("params");
        if (toolName != null) {
            params.put("name", toolName);
            params.set("arguments", arguments.deepCopy());
        }
        ObjectNode metadata = params.putObject("_meta");
        metadata.put(PROTOCOL_VERSION_META_KEY, McpPlatformContract.PROTOCOL_VERSION);
        metadata.putObject(CLIENT_INFO_META_KEY)
                .put("name", McpPlatformContract.CLIENT_NAME)
                .put("version", "0.1.0");
        metadata.putObject(CLIENT_CAPABILITIES_META_KEY);

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", authorization);
        headers.put("Content-Type", "application/json");
        headers.put("Accept", "application/json, text/event-stream");
        headers.put("MCP-Protocol-Version", McpPlatformContract.PROTOCOL_VERSION);
        headers.put("Mcp-Method", method);
        if (toolName != null) {
            headers.put("Mcp-Name", toolName);
        }

        McpHttpTransport.Response response;
        try {
            response = transport.post(
                    endpoint,
                    Map.copyOf(headers),
                    objectMapper.writeValueAsString(request),
                    requestTimeout,
                    maxResponseBytes);
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new McpPlatformException("MCP platform request was interrupted.", exception);
        }
        catch (IOException exception) {
            throw new McpPlatformException("MCP platform request failed.", exception);
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new McpPlatformException("MCP platform returned a non-success status.");
        }

        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(response.body());
        }
        catch (JsonProcessingException exception) {
            throw new McpPlatformException("MCP platform returned an invalid response.", exception);
        }
        if (!"2.0".equals(envelope.path("jsonrpc").asText())
                || !requestId.equals(envelope.path("id").asText())
                || envelope.has("error")
                || !envelope.path("result").isObject()) {
            throw new McpPlatformException("MCP platform returned an invalid response envelope.");
        }
        return envelope.path("result");
    }

    private static void validateDiscovery(JsonNode result) {
        boolean supportsProtocol = false;
        JsonNode supportedVersions = result.path("supportedVersions");
        if (supportedVersions.isArray()) {
            for (JsonNode version : supportedVersions) {
                supportsProtocol |= McpPlatformContract.PROTOCOL_VERSION.equals(version.asText());
            }
        }
        String serverName = result.path("_meta").path(SERVER_INFO_META_KEY).path("name").asText();
        if (!supportsProtocol || !McpPlatformContract.SERVER_NAME.equals(serverName)) {
            throw new McpPlatformException("MCP platform discovery contract was rejected.");
        }
    }

    private static Set<String> validateCatalog(JsonNode result) {
        JsonNode tools = result.path("tools");
        if (!tools.isArray()) {
            throw new McpPlatformException("MCP platform catalog contract was rejected.");
        }
        Set<String> exposedTools = new HashSet<>();
        for (JsonNode tool : tools) {
            String name = tool.path("name").asText();
            if (name.isBlank()
                    || McpPlatformContract.packageFor(name) == null
                    || !exposedTools.add(name)) {
                throw new McpPlatformException("MCP platform catalog contract was rejected.");
            }
        }
        if (!exposedTools.equals(McpPlatformContract.allowedToolNames())) {
            throw new McpPlatformException("MCP platform catalog contract was rejected.");
        }
        return Set.copyOf(exposedTools);
    }
}

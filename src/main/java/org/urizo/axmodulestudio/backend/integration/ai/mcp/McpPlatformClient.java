package org.urizo.axmodulestudio.backend.integration.ai.mcp;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public final class McpPlatformClient {

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

    private JsonNode invoke(String method) {
        String requestId = "axms-mcp-" + requestSequence.incrementAndGet();
        ObjectNode request = objectMapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", requestId);
        request.put("method", method);
        ObjectNode metadata = request.putObject("params").putObject("_meta");
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
        return Set.copyOf(exposedTools);
    }
}

package org.urizo.axmodulestudio.backend.integration.ai.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class McpPlatformClientTest {

    private static final URI ENDPOINT = URI.create("http://mcp-server:8091/mcp");
    private static final String TOKEN = "test-token-abcdefghijklmnopqrstuvwxyz-0123456789";
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void discoversTheApprovedServerAndAcceptsAnEmptyBootstrapCatalog() throws Exception {
        FakeTransport transport = new FakeTransport(
                success("axms-mcp-1", discoveryResult()),
                success("axms-mcp-2", "{\"resultType\":\"complete\",\"tools\":[]}"));

        McpPlatformContract.Snapshot snapshot = client(transport).probeCatalog();

        assertThat(snapshot.protocolVersion()).isEqualTo(McpPlatformContract.PROTOCOL_VERSION);
        assertThat(snapshot.serverName()).isEqualTo(McpPlatformContract.SERVER_NAME);
        assertThat(snapshot.exposedTools()).isEmpty();
        assertThat(transport.requests).hasSize(2);
        assertThat(transport.requests.get(0).headers())
                .containsEntry("Authorization", "Bearer " + TOKEN)
                .containsEntry("MCP-Protocol-Version", "2026-07-28")
                .containsEntry("Mcp-Method", "server/discover");
        assertThat(transport.requests.get(1).headers()).containsEntry("Mcp-Method", "tools/list");

        JsonNode discoveryRequest = objectMapper.readTree(transport.requests.get(0).body());
        assertThat(discoveryRequest.path("params").path("_meta")
                .path("io.modelcontextprotocol/protocolVersion").asText()).isEqualTo("2026-07-28");
        assertThat(discoveryRequest.path("params").path("_meta")
                .path("io.modelcontextprotocol/clientCapabilities").isObject()).isTrue();
    }

    @Test
    void acceptsOnlyThePreviouslyApprovedToolNames() {
        FakeTransport transport = new FakeTransport(
                success("axms-mcp-1", discoveryResult()),
                success("axms-mcp-2", "{\"resultType\":\"complete\",\"tools\":[{\"name\":\"read_file\"},{\"name\":\"create_cms_preview\"}]}"));

        assertThat(client(transport).probeCatalog().exposedTools())
                .containsExactlyInAnyOrder("read_file", "create_cms_preview");
        assertThat(McpPlatformContract.packageFor("read_file")).isEqualTo("coding");
        assertThat(McpPlatformContract.packageFor("create_cms_preview")).isEqualTo("cms");
        assertThat(McpPlatformContract.allowedToolNames()).hasSize(13);
    }

    @Test
    void rejectsUnknownOrDuplicateCatalogEntries() {
        FakeTransport unknown = new FakeTransport(
                success("axms-mcp-1", discoveryResult()),
                success("axms-mcp-2", "{\"resultType\":\"complete\",\"tools\":[{\"name\":\"arbitrary_shell\"}]}"));
        assertThatThrownBy(() -> client(unknown).probeCatalog())
                .isInstanceOf(McpPlatformException.class)
                .hasMessage("MCP platform catalog contract was rejected.");

        FakeTransport duplicate = new FakeTransport(
                success("axms-mcp-1", discoveryResult()),
                success("axms-mcp-2", "{\"resultType\":\"complete\",\"tools\":[{\"name\":\"read_file\"},{\"name\":\"read_file\"}]}"));
        assertThatThrownBy(() -> client(duplicate).probeCatalog())
                .isInstanceOf(McpPlatformException.class)
                .hasMessage("MCP platform catalog contract was rejected.");
    }

    @Test
    void callsOnlyARegisteredCodingToolWithExactArguments() throws Exception {
        FakeTransport transport = new FakeTransport(success(
                "axms-mcp-1",
                "{\"isError\":false,\"structuredContent\":{"
                        + "\"path\":\"README.md\",\"content\":\"ok\"}}"));
        JsonNode arguments = objectMapper.createObjectNode()
                .put("workspace", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
                .put("expectedHead", "1111111111111111111111111111111111111111")
                .put("path", "README.md");

        JsonNode result = client(transport).callTool("read_file", arguments);

        assertThat(result.path("structuredContent").path("content").asText()).isEqualTo("ok");
        assertThat(transport.requests).hasSize(1);
        assertThat(transport.requests.get(0).headers())
                .containsEntry("Mcp-Method", "tools/call")
                .containsEntry("Mcp-Name", "read_file");
        JsonNode request = objectMapper.readTree(transport.requests.get(0).body());
        assertThat(request.path("params").path("name").asText()).isEqualTo("read_file");
        assertThat(request.path("params").path("arguments")).isEqualTo(arguments);
    }

    @Test
    void rejectsUnknownToolsAndArgumentFieldsBeforeTransport() {
        FakeTransport transport = new FakeTransport();
        JsonNode safe = objectMapper.createObjectNode()
                .put("workspace", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
                .put("expectedHead", "1111111111111111111111111111111111111111")
                .put("path", "README.md");
        assertThatThrownBy(() -> client(transport).callTool("arbitrary_shell", safe))
                .isInstanceOf(McpPlatformException.class)
                .hasMessage("MCP coding tool call contract was rejected.");

        JsonNode injected = safe.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) injected)
                .put("command", "unregistered");
        assertThatThrownBy(() -> client(transport).callTool("read_file", injected))
                .isInstanceOf(McpPlatformException.class)
                .hasMessage("MCP coding tool call contract was rejected.");
        assertThat(transport.requests).isEmpty();
    }

    @Test
    void rejectsTheWrongServerOrProtocol() {
        FakeTransport wrongServer = new FakeTransport(
                success("axms-mcp-1", discoveryResult().replace(McpPlatformContract.SERVER_NAME, "other-server")));
        assertThatThrownBy(() -> client(wrongServer).probeCatalog())
                .isInstanceOf(McpPlatformException.class)
                .hasMessage("MCP platform discovery contract was rejected.");

        FakeTransport wrongProtocol = new FakeTransport(
                success("axms-mcp-1", discoveryResult().replace("2026-07-28", "2025-11-25")));
        assertThatThrownBy(() -> client(wrongProtocol).probeCatalog())
                .isInstanceOf(McpPlatformException.class)
                .hasMessage("MCP platform discovery contract was rejected.");
    }

    @Test
    void rejectsNonSuccessMalformedAndMismatchedResponsesWithoutEchoingBodiesOrTokens() {
        FakeTransport nonSuccess = new FakeTransport(new McpHttpTransport.Response(401, "secret-response-body"));
        assertThatThrownBy(() -> client(nonSuccess).probeCatalog())
                .isInstanceOf(McpPlatformException.class)
                .hasMessage("MCP platform returned a non-success status.")
                .hasMessageNotContaining("secret-response-body")
                .hasMessageNotContaining(TOKEN);

        FakeTransport malformed = new FakeTransport(new McpHttpTransport.Response(200, "not-json-secret"));
        assertThatThrownBy(() -> client(malformed).probeCatalog())
                .isInstanceOf(McpPlatformException.class)
                .hasMessage("MCP platform returned an invalid response.")
                .hasMessageNotContaining("not-json-secret");

        FakeTransport mismatched = new FakeTransport(success("wrong-id", discoveryResult()));
        assertThatThrownBy(() -> client(mismatched).probeCatalog())
                .isInstanceOf(McpPlatformException.class)
                .hasMessage("MCP platform returned an invalid response envelope.");
    }

    @Test
    void rejectsInvalidEndpointAndBoundsBeforeCreatingTheClient() {
        assertThatThrownBy(() -> new McpPlatformProperties(
                URI.create("http://mcp-server:8091/other"),
                java.nio.file.Path.of("token"),
                Duration.ofSeconds(2),
                Duration.ofSeconds(3),
                65_536))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new McpPlatformProperties(
                ENDPOINT,
                java.nio.file.Path.of("token"),
                Duration.ofSeconds(2),
                Duration.ofSeconds(3),
                10_000_000))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private McpPlatformClient client(FakeTransport transport) {
        return new McpPlatformClient(
                ENDPOINT,
                TOKEN,
                Duration.ofSeconds(3),
                65_536,
                transport,
                objectMapper);
    }

    private static String discoveryResult() {
        return "{\"resultType\":\"complete\",\"supportedVersions\":[\"2026-07-28\"],"
                + "\"capabilities\":{\"tools\":{}},\"_meta\":{\"io.modelcontextprotocol/serverInfo\":{"
                + "\"name\":\"urizo-final-mcp-server\",\"version\":\"0.1.0\"}}}";
    }

    private static McpHttpTransport.Response success(String id, String result) {
        return new McpHttpTransport.Response(200,
                "{\"jsonrpc\":\"2.0\",\"id\":\"" + id + "\",\"result\":" + result + "}");
    }

    private static final class FakeTransport implements McpHttpTransport {

        private final Queue<Response> responses = new ArrayDeque<>();
        private final List<Request> requests = new ArrayList<>();

        private FakeTransport(Response... responses) {
            this.responses.addAll(List.of(responses));
        }

        @Override
        public Response post(
                URI endpoint,
                Map<String, String> headers,
                String requestBody,
                Duration timeout,
                int maxResponseBytes) throws IOException {
            requests.add(new Request(endpoint, headers, requestBody, timeout, maxResponseBytes));
            Response response = responses.poll();
            if (response == null) {
                throw new IOException("No fake response was configured.");
            }
            return response;
        }
    }

    private record Request(
            URI endpoint,
            Map<String, String> headers,
            String body,
            Duration timeout,
            int maxResponseBytes) {
    }
}

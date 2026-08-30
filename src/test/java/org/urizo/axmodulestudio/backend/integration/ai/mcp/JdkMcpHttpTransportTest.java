package org.urizo.axmodulestudio.backend.integration.ai.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class JdkMcpHttpTransportTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void doesNotFollowRedirects() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            exchange.getResponseHeaders().add("Location", "/redirected");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/redirected", exchange -> {
            byte[] body = "unexpected".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        McpHttpTransport.Response response = transport().post(
                endpoint(),
                Map.of("Content-Type", "application/json"),
                "{}",
                Duration.ofSeconds(2),
                1_024);

        assertThat(response.statusCode()).isEqualTo(302);
    }

    @Test
    void rejectsOversizedBodies() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            byte[] body = new byte[2_048];
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        assertThatThrownBy(() -> transport().post(
                endpoint(),
                Map.of("Content-Type", "application/json"),
                "{}",
                Duration.ofSeconds(2),
                1_024))
                .isInstanceOf(IOException.class)
                .hasMessage("MCP response exceeded the configured size limit.");
    }

    private JdkMcpHttpTransport transport() {
        return new JdkMcpHttpTransport(Duration.ofSeconds(2));
    }

    private URI endpoint() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp");
    }
}

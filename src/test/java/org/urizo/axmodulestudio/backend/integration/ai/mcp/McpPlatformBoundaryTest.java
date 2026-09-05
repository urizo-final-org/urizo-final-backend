package org.urizo.axmodulestudio.backend.integration.ai.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class McpPlatformBoundaryTest {

    @Test
    void backendAdapterHasNoDatabaseOrPersistenceDependency() throws IOException {
        Path sourceRoot = Path.of("src", "main", "java", "org", "urizo", "axmodulestudio", "backend",
                "integration", "ai", "mcp");
        String sources;
        try (var files = Files.walk(sourceRoot)) {
            sources = files.filter(path -> path.toString().endsWith(".java"))
                    .map(this::read)
                    .reduce("", (left, right) -> left + "\n" + right)
                    .toLowerCase();
        }

        assertThat(sources)
                .doesNotContain("javax.sql")
                .doesNotContain("jakarta.persistence")
                .doesNotContain("jdbctemplate")
                .doesNotContain("datasource")
                .doesNotContain("postgresql");
    }

    @Test
    void mcpContainerHasOnlyItsDedicatedInternalNetworkAndCredential() throws IOException {
        String compose = Files.readString(Path.of("compose.dev.yaml"), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
        int start = compose.indexOf("\n  mcp-server:");
        int end = compose.indexOf("\n  coding-runtime:", start);
        assertThat(start).isGreaterThanOrEqualTo(0);
        assertThat(end).isGreaterThan(start);
        String service = compose.substring(start, end).toLowerCase();

        assertThat(service)
                .contains("- mcp_service_token")
                .contains("- axms_mcp")
                .doesNotContain("axms_internal")
                .doesNotContain("postgres")
                .doesNotContain("jdbc")
                .doesNotContain("valkey")
                .doesNotContain("checkpoint")
                .doesNotContain("ports:");
        assertThat(compose).contains("axms_mcp:\n    name: axms-spring-dev-mcp\n    internal: true");
    }

    private String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        }
        catch (IOException exception) {
            throw new IllegalStateException("Unable to read MCP boundary source.", exception);
        }
    }
}

package org.urizo.axmodulestudio.backend.integration.ai.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class McpPlatformLiveRoundTripTest {

    @Test
    void springClientDiscoversTheRealMcpServiceAndItsEmptyBootstrapCatalog() throws Exception {
        String endpoint = System.getProperty("axms.mcp.live.endpoint");
        String credentialFile = System.getProperty("axms.mcp.live.credential-file");
        Assumptions.assumeTrue(endpoint != null && credentialFile != null,
                "Live MCP endpoint and credential file were not provided.");

        String token = Files.readString(Path.of(credentialFile), StandardCharsets.UTF_8).trim();
        McpPlatformClient client = new McpPlatformClient(
                URI.create(endpoint),
                token,
                Duration.ofSeconds(5),
                65_536,
                new JdkMcpHttpTransport(Duration.ofSeconds(3)),
                new ObjectMapper());

        McpPlatformContract.Snapshot snapshot = client.probeCatalog();

        assertThat(snapshot.protocolVersion()).isEqualTo("2026-07-28");
        assertThat(snapshot.serverName()).isEqualTo("urizo-final-mcp-server");
        assertThat(snapshot.exposedTools()).isEmpty();
    }
}

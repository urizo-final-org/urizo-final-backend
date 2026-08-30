package org.urizo.axmodulestudio.backend.integration.ai.mcp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "ax.ai.mcp-platform.enabled", havingValue = "true")
@EnableConfigurationProperties(McpPlatformProperties.class)
public class McpPlatformConfiguration {

    @Bean
    McpHttpTransport mcpHttpTransport(McpPlatformProperties properties) {
        return new JdkMcpHttpTransport(properties.connectTimeout());
    }

    @Bean
    McpPlatformClient mcpPlatformClient(
            McpPlatformProperties properties,
            McpHttpTransport transport,
            ObjectMapper objectMapper) throws IOException {
        String token = Files.readString(properties.credentialFile(), StandardCharsets.UTF_8).trim();
        if (token.length() < 43 || token.length() > 512 || token.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalStateException("MCP service credential file is invalid.");
        }
        return new McpPlatformClient(
                properties.endpoint(),
                token,
                properties.requestTimeout(),
                properties.maxResponseBytes(),
                transport,
                objectMapper);
    }
}

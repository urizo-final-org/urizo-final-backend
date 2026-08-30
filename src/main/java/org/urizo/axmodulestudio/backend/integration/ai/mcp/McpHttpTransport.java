package org.urizo.axmodulestudio.backend.integration.ai.mcp;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Map;

interface McpHttpTransport {

    Response post(
            URI endpoint,
            Map<String, String> headers,
            String requestBody,
            Duration timeout,
            int maxResponseBytes) throws IOException, InterruptedException;

    record Response(int statusCode, String body) {
    }
}

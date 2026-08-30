package org.urizo.axmodulestudio.backend.integration.ai.mcp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

final class JdkMcpHttpTransport implements McpHttpTransport {

    private final HttpClient httpClient;

    JdkMcpHttpTransport(Duration connectTimeout) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public Response post(
            URI endpoint,
            Map<String, String> headers,
            String requestBody,
            Duration timeout,
            int maxResponseBytes) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8));
        headers.forEach(request::header);

        HttpResponse<InputStream> response = httpClient.send(
                request.build(),
                HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            return new Response(response.statusCode(), readBounded(body, maxResponseBytes));
        }
    }

    private static String readBounded(InputStream input, int maximumBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maximumBytes, 8_192));
        byte[] buffer = new byte[4_096];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > maximumBytes) {
                throw new IOException("MCP response exceeded the configured size limit.");
            }
            output.write(buffer, 0, read);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}

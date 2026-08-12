package org.urizo.axmodulestudio.backend.dev.cms;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("dev")
class JdkProviderHttpTransport implements ProviderHttpTransport {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @Override
    public ProviderHttpResponse exchange(
            String method,
            URI endpoint,
            Map<String, String> headers,
            String requestBody,
            Duration timeout) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint).timeout(timeout);
        headers.forEach(request::header);
        if ("GET".equals(method)) {
            request.GET();
        }
        else if ("POST".equals(method)) {
            request.POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8));
        }
        else {
            throw new IllegalArgumentException("Unsupported provider HTTP method.");
        }

        HttpResponse<String> response = httpClient.send(
                request.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new ProviderHttpResponse(response.statusCode(), response.body());
    }
}

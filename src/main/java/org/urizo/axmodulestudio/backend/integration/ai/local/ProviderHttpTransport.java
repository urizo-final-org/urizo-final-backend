package org.urizo.axmodulestudio.backend.integration.ai.local;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Map;

interface ProviderHttpTransport {

    ProviderHttpResponse exchange(
            String method,
            URI endpoint,
            Map<String, String> headers,
            String requestBody,
            Duration timeout) throws IOException, InterruptedException;

    record ProviderHttpResponse(int statusCode, String body) {
    }
}

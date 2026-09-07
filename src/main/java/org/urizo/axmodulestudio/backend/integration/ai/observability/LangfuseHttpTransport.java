package org.urizo.axmodulestudio.backend.integration.ai.observability;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Map;

interface LangfuseHttpTransport {

    Response get(
            URI endpoint,
            Map<String, String> headers,
            Duration timeout,
            int maxResponseBytes) throws IOException, InterruptedException;

    record Response(int statusCode, String body) { }
}

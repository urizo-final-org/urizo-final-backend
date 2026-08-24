package org.urizo.axmodulestudio.backend.auth.security;

import java.io.IOException;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.urizo.axmodulestudio.backend.core.web.TraceIdFilter;

final class SecurityErrorWriter {
    private final ObjectMapper objectMapper;

    SecurityErrorWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    void write(HttpServletRequest request, HttpServletResponse response, int status, String code,
            String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), Map.of(
                "schemaVersion", "1.0",
                "traceId", String.valueOf(request.getAttribute(TraceIdFilter.REQUEST_ATTRIBUTE)),
                "error", Map.of("code", code, "message", message, "retryable", false)));
    }
}

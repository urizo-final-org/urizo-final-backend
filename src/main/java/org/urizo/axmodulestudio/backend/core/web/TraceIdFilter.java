package org.urizo.axmodulestudio.backend.core.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class TraceIdFilter extends OncePerRequestFilter {

    public static final String HEADER_NAME = "X-Trace-Id";
    public static final String REQUEST_ATTRIBUTE = TraceIdFilter.class.getName() + ".traceId";

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$");

    private final ObjectMapper objectMapper;

    public TraceIdFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String suppliedTraceId = request.getHeader(HEADER_NAME);
        if (suppliedTraceId != null && !isValidUuid(suppliedTraceId)) {
            writeInvalidTraceId(response);
            return;
        }

        String traceId = suppliedTraceId == null ? UUID.randomUUID().toString() : suppliedTraceId;
        request.setAttribute(REQUEST_ATTRIBUTE, traceId);
        response.setHeader(HEADER_NAME, traceId);
        filterChain.doFilter(request, response);
    }

    private static boolean isValidUuid(String candidate) {
        if (!UUID_PATTERN.matcher(candidate).matches()) {
            return false;
        }
        try {
            UUID.fromString(candidate);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private void writeInvalidTraceId(HttpServletResponse response) throws IOException {
        String traceId = UUID.randomUUID().toString();
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        response.setHeader(HEADER_NAME, traceId);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), Map.of(
                "schemaVersion", "1.0",
                "traceId", traceId,
                "error", Map.of(
                        "code", "INVALID_TRACE_ID",
                        "message", "X-Trace-Id must be a valid UUID.",
                        "retryable", false)));
    }
}

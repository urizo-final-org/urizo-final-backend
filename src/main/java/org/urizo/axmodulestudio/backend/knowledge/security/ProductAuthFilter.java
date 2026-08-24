package org.urizo.axmodulestudio.backend.knowledge.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.urizo.axmodulestudio.backend.core.web.TraceIdFilter;
import org.urizo.axmodulestudio.backend.knowledge.dto.ProductApiContract;

@Component
@Profile("local-full & dev-session")
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
final class ProductAuthFilter extends OncePerRequestFilter {

    private final ProductLocalAccess access;
    private final ObjectMapper objectMapper;

    ProductAuthFilter(ProductLocalAccess access, ObjectMapper objectMapper) {
        this.access = access;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/")
                || path.equals("/api/health")
                || path.equals("/api/readiness");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (access.accepts(request.getHeader(HttpHeaders.AUTHORIZATION))) {
            filterChain.doFilter(request, response);
            return;
        }
        String trace = String.valueOf(request.getAttribute(TraceIdFilter.REQUEST_ATTRIBUTE));
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), new ProductApiContract.ErrorEnvelope(
                ProductApiContract.SCHEMA_VERSION,
                UUID.fromString(trace),
                new ProductApiContract.ErrorDetail(
                        "AUTHENTICATION_REQUIRED",
                        "A valid local product session is required.",
                        false,
                        null)));
    }
}

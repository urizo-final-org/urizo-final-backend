package org.urizo.axmodulestudio.backend.common.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.urizo.axmodulestudio.backend.common.web.TraceIdFilter;

/**
 * Keeps platform technical configuration in the delivery-company lane.
 *
 * <p>The rules live in one table rather than being spread across handlers, so the permission matrix
 * can be read in a single place and a new route cannot quietly skip its check.
 *
 * <p>Only platform-global operations are listed. Everything else an authenticated administrator
 * reaches is customer business operation, which both roles perform. Project-scoped narrowing is not
 * applied: this deployment serves one customer, so a {@code GENERAL_ADMIN} reaches every Project.
 */
public final class AdminAuthorizationFilter extends OncePerRequestFilter {

    static final String SCHEMA_VERSION = "1.0";

    /**
     * Routes reserved for the delivery-company technical role.
     *
     * <p>Each entry names the permission it enforces, so the rule and the approved matrix row stay
     * visibly connected.
     */
    private static final List<Rule> PLATFORM_ONLY = List.of(
            new Rule("POST", "/api/projects", AdminPermission.PROJECT_CREATE_OR_ARCHIVE),
            new Rule("POST", "/api/projects/*/connectors", AdminPermission.CONNECTOR_SPECIFICATION),
            new Rule("POST", "/api/connectors/*/preview", AdminPermission.CONNECTOR_TEST_OR_ACTIVATE),
            new Rule("POST", "/api/connectors/*/versions/*/activate",
                    AdminPermission.CONNECTOR_TEST_OR_ACTIVATE),
            new Rule("POST", "/api/connectors/*/sync", AdminPermission.CONNECTOR_TEST_OR_ACTIVATE),
            new Rule("POST", "/api/knowledge-bases/*/versions",
                    AdminPermission.KNOWLEDGE_BUILD_CONTROL),
            new Rule("POST", "/api/knowledge-versions/*/activate",
                    AdminPermission.KNOWLEDGE_VERSION_TRANSITION),
            new Rule("POST", "/api/knowledge-bases/*/rollback",
                    AdminPermission.KNOWLEDGE_VERSION_TRANSITION));

    private static final AntPathMatcher PATHS = new AntPathMatcher();

    private final AuthorizationService authorization;
    private final ObjectMapper objectMapper;

    public AdminAuthorizationFilter(AuthorizationService authorization, ObjectMapper objectMapper) {
        this.authorization = authorization;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        AdminPermission required = requiredPermission(request);
        if (required == null) {
            filterChain.doFilter(request, response);
            return;
        }
        // An absent actor means authentication already let the route through as open, so there is
        // nothing here to authorize.
        Object actor = request.getAttribute(ProductionAuthFilter.ACTOR_ATTRIBUTE);
        if (!(actor instanceof ActorContext resolved)) {
            filterChain.doFilter(request, response);
            return;
        }
        try {
            authorization.authorize(resolved, required);
        }
        catch (AccessDeniedException ex) {
            writeForbidden(request, response);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private static AdminPermission requiredPermission(HttpServletRequest request) {
        String method = request.getMethod();
        String path = request.getRequestURI();
        return PLATFORM_ONLY.stream()
                .filter(rule -> rule.method().equalsIgnoreCase(method)
                        && PATHS.match(rule.pattern(), path))
                .map(Rule::permission)
                .findFirst()
                .orElse(null);
    }

    private void writeForbidden(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), Map.of(
                "schemaVersion", SCHEMA_VERSION,
                "traceId", String.valueOf(request.getAttribute(TraceIdFilter.REQUEST_ATTRIBUTE)),
                "error", Map.of(
                        "code", AccessDeniedException.CODE,
                        "message", "Platform technical configuration requires the "
                                + "delivery-company administrator role.",
                        "retryable", false)));
    }

    private record Rule(String method, String pattern, AdminPermission permission) {
    }
}

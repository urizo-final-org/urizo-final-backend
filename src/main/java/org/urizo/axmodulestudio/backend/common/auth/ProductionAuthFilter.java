package org.urizo.axmodulestudio.backend.common.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import org.urizo.axmodulestudio.backend.common.web.TraceIdFilter;

/**
 * Resolves the server-derived actor for every protected request.
 *
 * <p>The filter is intentionally not a component. Registration and its profile are decided by the
 * Integration/Contract owner, so adding this class cannot change the behavior of the running stack
 * on its own.
 *
 * <p>Health and readiness stay open because the container health check reaches them without a
 * session, matching the exclusion the local acceptance filter already applies.
 */
public final class ProductionAuthFilter extends OncePerRequestFilter {

    public static final String ACTOR_ATTRIBUTE = ProductionAuthFilter.class.getName() + ".actor";
    public static final String SESSION_EXPIRY_ATTRIBUTE =
            ProductionAuthFilter.class.getName() + ".sessionExpiresAt";

    static final String SCHEMA_VERSION = "1.0";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final Set<String> OPEN_PATHS = Set.of(
            "/api/health",
            "/api/readiness",
            "/api/auth/login");

    private final AuthenticationService authentication;
    private final ObjectMapper objectMapper;

    public ProductionAuthFilter(AuthenticationService authentication, ObjectMapper objectMapper) {
        this.authentication = authentication;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/") || OPEN_PATHS.contains(path);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        SessionIdentity identity;
        try {
            identity = authentication.resolveSession(bearerToken(request));
        }
        catch (AuthenticationFailedException ex) {
            writeUnauthenticated(request, response);
            return;
        }
        request.setAttribute(ACTOR_ATTRIBUTE, identity.actor());
        request.setAttribute(SESSION_EXPIRY_ATTRIBUTE, identity.expiresAt());
        filterChain.doFilter(request, response);
    }

    /**
     * Reads the actor a completed filter pass stored on the request.
     *
     * @throws AuthenticationFailedException when the request never passed authentication, so a
     *     protected handler can never observe an absent actor as an anonymous one
     */
    public static ActorContext requireActor(HttpServletRequest request) {
        Object actor = request.getAttribute(ACTOR_ATTRIBUTE);
        if (actor instanceof ActorContext resolved) {
            return resolved;
        }
        throw new AuthenticationFailedException("A valid session is required.");
    }

    /**
     * Reads the session expiry a completed filter pass stored on the request.
     *
     * @throws AuthenticationFailedException when the request never passed authentication
     */
    public static Instant requireSessionExpiry(HttpServletRequest request) {
        Object expiresAt = request.getAttribute(SESSION_EXPIRY_ATTRIBUTE);
        if (expiresAt instanceof Instant resolved) {
            return resolved;
        }
        throw new AuthenticationFailedException("A valid session is required.");
    }

    private static String bearerToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        return header.substring(BEARER_PREFIX.length());
    }

    private void writeUnauthenticated(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), Map.of(
                "schemaVersion", SCHEMA_VERSION,
                "traceId", String.valueOf(request.getAttribute(TraceIdFilter.REQUEST_ATTRIBUTE)),
                "error", Map.of(
                        "code", AuthenticationFailedException.CODE,
                        "message", "A valid administrator session is required.",
                        "retryable", false)));
    }
}

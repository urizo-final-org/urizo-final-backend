package org.urizo.axmodulestudio.backend.common.auth;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.urizo.axmodulestudio.backend.common.web.TraceIdFilter;

/**
 * Production login, logout, and current-session identity.
 *
 * <p>Login is the only unauthenticated product endpoint: it is the operation that issues the session
 * every other route requires. Logout and the current-session read take their actor from the
 * completed authentication pass rather than from anything the client sends.
 */
@RestController
@Validated
@Profile("local-full")
@RequestMapping("/api/auth")
public class AuthApiController {

    private static final String IDEMPOTENCY = "Idempotency-Key";
    private static final String BEARER_PREFIX = "Bearer ";

    private final AuthenticationService authentication;

    AuthApiController(AuthenticationService authentication) {
        this.authentication = authentication;
    }

    @PostMapping("/login")
    AuthApiContract.LoginResponse login(
            @RequestHeader(IDEMPOTENCY) String key,
            @Valid @RequestBody AuthApiContract.LoginRequest body,
            HttpServletRequest request) {
        AuthApiContract.requireIdempotencyKey(key);
        IssuedSession session = authentication.login(
                body.loginId(), body.passwordValue().toCharArray());
        return new AuthApiContract.LoginResponse(
                AuthApiContract.SCHEMA_VERSION,
                trace(request),
                session.token(),
                "Bearer",
                session.expiresAt(),
                AuthApiContract.ActorView.of(session.actor()));
    }

    /**
     * Revokes the presented session.
     *
     * <p>Answering 204 for an already revoked or unknown session lets a client always reach a
     * signed-out state instead of being stuck holding a token it cannot discard.
     */
    @PostMapping("/logout")
    ResponseEntity<Void> logout(
            @RequestHeader(IDEMPOTENCY) String key, HttpServletRequest request) {
        AuthApiContract.requireIdempotencyKey(key);
        authentication.logout(bearerToken(request));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    AuthApiContract.CurrentSessionResponse currentSession(HttpServletRequest request) {
        ActorContext actor = ProductionAuthFilter.requireActor(request);
        return new AuthApiContract.CurrentSessionResponse(
                AuthApiContract.SCHEMA_VERSION,
                trace(request),
                AuthApiContract.ActorView.of(actor),
                ProductionAuthFilter.requireSessionExpiry(request));
    }

    private static String bearerToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        return header == null || !header.startsWith(BEARER_PREFIX)
                ? null
                : header.substring(BEARER_PREFIX.length());
    }

    private static UUID trace(HttpServletRequest request) {
        return UUID.fromString(String.valueOf(request.getAttribute(TraceIdFilter.REQUEST_ATTRIBUTE)));
    }
}

package org.urizo.axmodulestudio.backend.auth.controller;

import java.time.Duration;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.urizo.axmodulestudio.backend.auth.dto.request.LoginRequest;
import org.urizo.axmodulestudio.backend.auth.dto.response.ActorResponse;
import org.urizo.axmodulestudio.backend.auth.dto.response.CurrentSessionResponse;
import org.urizo.axmodulestudio.backend.auth.dto.response.LoginResponse;
import org.urizo.axmodulestudio.backend.auth.service.AuthService;
import org.urizo.axmodulestudio.backend.core.web.TraceIdFilter;
import org.urizo.axmodulestudio.backend.auth.config.JwtProperties;
import org.urizo.axmodulestudio.backend.auth.security.AuthenticatedActor;

@RestController
@Validated
@Profile("local-full & !dev-session")
@RequestMapping("/api/auth")
public class AuthController {

    public static final String REFRESH_COOKIE = "AXMS_REFRESH_TOKEN";
    private static final String SCHEMA_VERSION = "1.0";
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile(
            "^[A-Za-z0-9][A-Za-z0-9._:-]{7,127}$");

    private final AuthService authService;
    private final JwtProperties properties;

    public AuthController(AuthService authService, JwtProperties properties) {
        this.authService = authService;
        this.properties = properties;
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest servletRequest) {
        requireIdempotencyKey(idempotencyKey);
        AuthService.IssuedSession issued = authService.login(
                request.loginId(), request.passwordValue().toCharArray());
        return tokenResponse(issued, servletRequest);
    }

    @PostMapping("/refresh")
    public ResponseEntity<LoginResponse> refresh(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken,
            HttpServletRequest request) {
        requireIdempotencyKey(idempotencyKey);
        return tokenResponse(authService.refresh(refreshToken), request);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken,
            Authentication authentication) {
        requireIdempotencyKey(idempotencyKey);
        AuthenticatedActor actor = authenticatedActor(authentication);
        authService.logout(actor.actorId(), refreshToken);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, refreshCookie("", Duration.ZERO).toString())
                .build();
    }

    @GetMapping("/me")
    public CurrentSessionResponse currentSession(
            Authentication authentication, HttpServletRequest request) {
        AuthenticatedActor actor = authenticatedActor(authentication);
        if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)) {
            throw new org.urizo.axmodulestudio.backend.auth.service.AuthenticationFailedException(
                    "A valid administrator session is required.");
        }
        return new CurrentSessionResponse(
                SCHEMA_VERSION, trace(request), ActorResponse.from(actor),
                jwtAuthentication.getToken().getExpiresAt());
    }

    private ResponseEntity<LoginResponse> tokenResponse(
            AuthService.IssuedSession issued, HttpServletRequest request) {
        LoginResponse body = new LoginResponse(
                SCHEMA_VERSION,
                trace(request),
                issued.accessToken(),
                "Bearer",
                issued.accessExpiresAt(),
                ActorResponse.from(issued.actor()));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE,
                        refreshCookie(
                                issued.refreshToken(), properties.refreshTokenLifetime()).toString())
                .body(body);
    }

    private ResponseCookie refreshCookie(String value, Duration maxAge) {
        return ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true)
                .secure(properties.secureRefreshCookie())
                .sameSite("Strict")
                .path("/api/auth")
                .maxAge(maxAge)
                .build();
    }

    private static void requireIdempotencyKey(String key) {
        if (key == null || !IDEMPOTENCY_KEY.matcher(key).matches()) {
            throw new IllegalArgumentException(
                    "Idempotency-Key does not satisfy the public contract.");
        }
    }

    private AuthenticatedActor authenticatedActor(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new org.urizo.axmodulestudio.backend.auth.service.AuthenticationFailedException(
                    "A valid administrator session is required.");
        }
        try {
            return authService.loadActor(UUID.fromString(authentication.getName()));
        }
        catch (IllegalArgumentException ex) {
            throw new org.urizo.axmodulestudio.backend.auth.service.AuthenticationFailedException(
                    "A valid administrator session is required.");
        }
    }

    private static UUID trace(HttpServletRequest request) {
        return UUID.fromString(String.valueOf(
                request.getAttribute(TraceIdFilter.REQUEST_ATTRIBUTE)));
    }
}

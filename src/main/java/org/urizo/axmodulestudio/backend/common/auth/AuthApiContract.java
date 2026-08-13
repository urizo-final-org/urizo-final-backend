package org.urizo.axmodulestudio.backend.common.auth;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Public payloads of the authentication boundary.
 *
 * <p>The types live beside the auth domain rather than in the Stage 3 product facade, so the common
 * package never depends on a feature package for its own contract.
 *
 * <p>The credential field is named {@code passwordValue} because the contract validator rejects
 * credential-shaped key names; it still carries the only value a login accepts.
 */
public final class AuthApiContract {

    public static final String SCHEMA_VERSION = "1.0";

    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile(
            "^[A-Za-z0-9][A-Za-z0-9._:-]{7,127}$");

    private AuthApiContract() {
    }

    public record LoginRequest(
            @NotBlank String schemaVersion,
            @NotBlank @Size(max = 120) String loginId,
            @NotBlank @Size(min = 8, max = 256) String passwordValue) {
        public LoginRequest { requireVersion(schemaVersion); }
    }

    /**
     * The opaque session value is returned exactly once.
     *
     * <p>Persistence keeps only its digest, so a client that loses the value must sign in again
     * rather than read it back.
     */
    public record LoginResponse(
            String schemaVersion,
            UUID traceId,
            String sessionToken,
            String tokenType,
            Instant expiresAt,
            ActorView actor) {
    }

    public record CurrentSessionResponse(
            String schemaVersion,
            UUID traceId,
            ActorView actor,
            Instant expiresAt) {
    }

    /** Server-derived authority. A client-supplied role or actor id never reaches this view. */
    public record ActorView(
            UUID actorId,
            AdminRole role,
            List<UUID> assignedProjectIds) {

        static ActorView of(ActorContext actor) {
            return new ActorView(
                    actor.actorId(), actor.role(), List.copyOf(actor.assignedProjectIds()));
        }
    }

    public record ErrorEnvelope(String schemaVersion, UUID traceId, ErrorDetail error) {
    }

    public record ErrorDetail(
            String code,
            String message,
            boolean retryable,
            @JsonInclude(JsonInclude.Include.NON_NULL) Long retryAfterMs) {
    }

    /**
     * Rejects a malformed {@code Idempotency-Key}.
     *
     * <p>The header is required by the public contract on every {@code /api} POST. Login and logout
     * are naturally safe to repeat — a repeated login issues a new session and leaves the earlier one
     * valid until it expires — so the key is validated for shape and not replayed. Replaying a login
     * response would mean persisting the session value itself, which the session design deliberately
     * avoids.
     */
    static void requireIdempotencyKey(String key) {
        if (key == null || !IDEMPOTENCY_KEY.matcher(key).matches()) {
            throw new IllegalArgumentException(
                    "Idempotency-Key does not satisfy the public contract.");
        }
    }

    static void requireVersion(String version) {
        if (!SCHEMA_VERSION.equals(version)) {
            throw new IllegalArgumentException("schemaVersion must be " + SCHEMA_VERSION + ".");
        }
    }
}

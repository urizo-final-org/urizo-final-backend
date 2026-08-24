package org.urizo.axmodulestudio.backend.auth.security;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.urizo.axmodulestudio.backend.auth.service.AuthenticationFailedException;
import org.urizo.axmodulestudio.backend.auth.config.JwtProperties;

/** Issues access/refresh JWTs; decoder beans independently validate typ and audience. */
public final class JwtTokenProvider {
    public static final String TOKEN_TYPE_CLAIM = "typ";
    public static final String ACCESS_TYPE = "access";
    public static final String REFRESH_TYPE = "refresh";

    private final JwtEncoder encoder;
    private final JwtDecoder refreshDecoder;
    private final JwtProperties properties;
    private final Clock clock;

    public JwtTokenProvider(
            JwtEncoder encoder,
            @Qualifier("refreshJwtDecoder") JwtDecoder refreshDecoder,
            JwtProperties properties,
            Clock clock) {
        this.encoder = encoder;
        this.refreshDecoder = refreshDecoder;
        this.properties = properties;
        this.clock = clock;
    }

    public IssuedJwtPair issue(AuthenticatedActor actor) {
        Instant issuedAt = Instant.now(clock);
        Instant accessExpiresAt = issuedAt.plus(properties.accessTokenLifetime());
        Instant refreshExpiresAt = issuedAt.plus(properties.refreshTokenLifetime());
        UUID refreshJwtId = UUID.randomUUID();
        return new IssuedJwtPair(
                encode(actor, UUID.randomUUID(), ACCESS_TYPE, properties.accessAudience(),
                        issuedAt, accessExpiresAt),
                accessExpiresAt,
                encode(actor, refreshJwtId, REFRESH_TYPE, properties.refreshAudience(),
                        issuedAt, refreshExpiresAt),
                refreshExpiresAt,
                refreshJwtId,
                issuedAt);
    }

    public RefreshIdentity decodeRefresh(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new AuthenticationFailedException("A valid refresh token is required.");
        }
        try {
            Jwt jwt = refreshDecoder.decode(rawToken);
            return new RefreshIdentity(uuid(jwt.getId()), uuid(jwt.getSubject()));
        }
        catch (JwtException | IllegalArgumentException ex) {
            throw new AuthenticationFailedException("A valid refresh token is required.");
        }
    }

    private String encode(
            AuthenticatedActor actor,
            UUID jwtId,
            String tokenType,
            String audience,
            Instant issuedAt,
            Instant expiresAt) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .subject(actor.actorId().toString())
                .audience(List.of(audience))
                .id(jwtId.toString())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim(TOKEN_TYPE_CLAIM, tokenType)
                .claim("role", actor.role().name())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private static UUID uuid(String value) {
        try {
            return UUID.fromString(value);
        }
        catch (RuntimeException ex) {
            throw new AuthenticationFailedException("A valid JWT identity is required.");
        }
    }

    public record IssuedJwtPair(
            String accessToken,
            Instant accessExpiresAt,
            String refreshToken,
            Instant refreshExpiresAt,
            UUID refreshJwtId,
            Instant issuedAt) {
    }

    public record RefreshIdentity(UUID jwtId, UUID accountId) {
    }
}

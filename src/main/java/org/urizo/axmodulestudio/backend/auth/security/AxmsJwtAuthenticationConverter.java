package org.urizo.axmodulestudio.backend.auth.security;

import java.util.List;
import java.util.UUID;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.urizo.axmodulestudio.backend.auth.service.AuthService;
import org.urizo.axmodulestudio.backend.auth.service.AuthenticationFailedException;

/** Ignores the JWT role claim and reloads current authority from JPA. */
public final class AxmsJwtAuthenticationConverter
        implements Converter<Jwt, AbstractAuthenticationToken> {
    private final AuthService authService;

    public AxmsJwtAuthenticationConverter(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        try {
            AuthenticatedActor actor = authService.loadActor(UUID.fromString(jwt.getSubject()));
            return new JwtAuthenticationToken(
                    jwt,
                    List.of(new SimpleGrantedAuthority("ROLE_" + actor.role().name())),
                    actor.actorId().toString());
        }
        catch (AuthenticationFailedException | IllegalArgumentException ex) {
            throw new OAuth2AuthenticationException(
                    new OAuth2Error("invalid_token"), "The access token is no longer usable.");
        }
    }
}

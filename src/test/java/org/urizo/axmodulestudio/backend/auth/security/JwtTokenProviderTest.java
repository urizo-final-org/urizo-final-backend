package org.urizo.axmodulestudio.backend.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.urizo.axmodulestudio.backend.auth.entity.AdminRole;
import org.urizo.axmodulestudio.backend.auth.service.AuthenticationFailedException;

class JwtTokenProviderTest {

    @Test
    void separatesAccessAndRefreshByAudienceTypeAndLifetime() {
        Instant issuedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        JwtTestSupport.Bundle jwt = JwtTestSupport.bundle(
                Clock.fixed(issuedAt, ZoneOffset.UTC));
        AuthenticatedActor actor = new AuthenticatedActor(
                UUID.randomUUID(), "일반 관리자", AdminRole.GENERAL_ADMIN);

        JwtTokenProvider.IssuedJwtPair pair = jwt.provider().issue(actor);
        Jwt access = jwt.accessDecoder().decode(pair.accessToken());
        Jwt refresh = jwt.refreshDecoder().decode(pair.refreshToken());

        assertThat(pair.accessExpiresAt()).isEqualTo(issuedAt.plusSeconds(15 * 60));
        assertThat(pair.refreshExpiresAt()).isEqualTo(issuedAt.plusSeconds(7 * 24 * 60 * 60));
        assertThat(access.getAudience()).containsExactly("axms-api");
        assertThat(access.getClaimAsString(JwtTokenProvider.TOKEN_TYPE_CLAIM))
                .isEqualTo(JwtTokenProvider.ACCESS_TYPE);
        assertThat(refresh.getAudience()).containsExactly("axms-refresh");
        assertThat(refresh.getClaimAsString(JwtTokenProvider.TOKEN_TYPE_CLAIM))
                .isEqualTo(JwtTokenProvider.REFRESH_TYPE);
        assertThat(refresh.getId()).isEqualTo(pair.refreshJwtId().toString());
        assertThat(refresh.getSubject()).isEqualTo(actor.actorId().toString());
        assertThat(pair.accessToken().split("\\.")).hasSize(3);
        assertThat(pair.refreshToken().split("\\.")).hasSize(3);
    }

    @Test
    void neitherDecoderAcceptsTheOtherTokenClass() {
        Instant issuedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        JwtTestSupport.Bundle jwt = JwtTestSupport.bundle(
                Clock.fixed(issuedAt, ZoneOffset.UTC));
        JwtTokenProvider.IssuedJwtPair pair = jwt.provider().issue(new AuthenticatedActor(
                UUID.randomUUID(), "최고 관리자", AdminRole.SUPER_ADMIN));

        assertThatThrownBy(() -> jwt.accessDecoder().decode(pair.refreshToken()))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> jwt.refreshDecoder().decode(pair.accessToken()))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> jwt.provider().decodeRefresh(pair.accessToken()))
                .isInstanceOf(AuthenticationFailedException.class)
                .hasMessage("A valid refresh token is required.");
    }

    @Test
    void aModifiedSignatureNeverDecodes() {
        Instant issuedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        JwtTestSupport.Bundle jwt = JwtTestSupport.bundle(
                Clock.fixed(issuedAt, ZoneOffset.UTC));
        String refresh = jwt.provider().issue(new AuthenticatedActor(
                UUID.randomUUID(), "최고 관리자", AdminRole.SUPER_ADMIN)).refreshToken();
        char replacement = refresh.endsWith("A") ? 'B' : 'A';
        String forged = refresh.substring(0, refresh.length() - 1) + replacement;

        assertThatThrownBy(() -> jwt.provider().decodeRefresh(forged))
                .isInstanceOf(AuthenticationFailedException.class);
    }
}

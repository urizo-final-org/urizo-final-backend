package org.urizo.axmodulestudio.backend.auth.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.urizo.axmodulestudio.backend.auth.entity.RefreshTokenStatus.ACTIVE;
import static org.urizo.axmodulestudio.backend.auth.entity.RefreshTokenStatus.REVOKED;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.urizo.axmodulestudio.backend.auth.entity.AccountStatus;
import org.urizo.axmodulestudio.backend.auth.entity.AdminAccountEntity;
import org.urizo.axmodulestudio.backend.auth.entity.AdminRole;
import org.urizo.axmodulestudio.backend.auth.entity.AdminSessionEntity;
import org.urizo.axmodulestudio.backend.auth.repository.AdminAccountRepository;
import org.urizo.axmodulestudio.backend.auth.repository.AdminSessionRepository;
import org.urizo.axmodulestudio.backend.auth.security.AuthenticatedActor;
import org.urizo.axmodulestudio.backend.auth.security.JwtTestSupport;
import org.urizo.axmodulestudio.backend.auth.security.JwtTokenProvider;

class AuthServiceImplTest {

    @Test
    void logoutWithRotatedAncestorRevokesActiveDescendantAndPreservesUnrelatedFamily() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        UUID accountId = UUID.randomUUID();
        AuthenticatedActor actor = new AuthenticatedActor(
                accountId, "일반 관리자", AdminRole.GENERAL_ADMIN);
        JwtTestSupport.Bundle jwt = JwtTestSupport.bundle(clock);
        JwtTokenProvider.IssuedJwtPair ancestorToken = jwt.provider().issue(actor);
        JwtTokenProvider.IssuedJwtPair descendantToken = jwt.provider().issue(actor);
        JwtTokenProvider.IssuedJwtPair unrelatedToken = jwt.provider().issue(actor);
        PasswordHasher passwordHasher = new PasswordHasher(1);

        AdminSessionEntity ancestor = session(
                accountId, ancestorToken, passwordHasher);
        AdminSessionEntity descendant = session(
                accountId, descendantToken, passwordHasher);
        AdminSessionEntity unrelated = session(
                accountId, unrelatedToken, passwordHasher);
        ancestor.rotate(now, descendant.getJwtId());

        AdminAccountRepository accounts = mock(AdminAccountRepository.class);
        AdminSessionRepository sessions = mock(AdminSessionRepository.class);
        AdminAccountEntity account = new AdminAccountEntity(
                accountId,
                "general-admin",
                "일반 관리자",
                "unused-password-hash",
                AdminRole.GENERAL_ADMIN,
                AccountStatus.ACTIVE,
                now);
        when(accounts.findByIdForUpdate(accountId)).thenReturn(Optional.of(account));
        Map<UUID, AdminSessionEntity> sessionsByJwtId = Map.of(
                ancestor.getJwtId(), ancestor,
                descendant.getJwtId(), descendant,
                unrelated.getJwtId(), unrelated);
        when(sessions.findByJwtIdForUpdate(any(UUID.class)))
                .thenAnswer(invocation -> Optional.ofNullable(
                        sessionsByJwtId.get(invocation.getArgument(0, UUID.class))));
        AuthServiceImpl service = new AuthServiceImpl(
                accounts, sessions, passwordHasher, jwt.provider(), clock);

        service.logout(accountId, ancestorToken.refreshToken());

        assertThat(List.of(descendant.getStatus(), unrelated.getStatus()))
                .containsExactly(REVOKED, ACTIVE);
        verify(sessions, never()).revokeActiveByAccountId(any(), any(), any(), any());
    }

    private static AdminSessionEntity session(
            UUID accountId,
            JwtTokenProvider.IssuedJwtPair token,
            PasswordHasher passwordHasher) {
        return new AdminSessionEntity(
                UUID.randomUUID(),
                accountId,
                token.refreshJwtId(),
                passwordHasher.digestToken(token.refreshToken()),
                token.issuedAt(),
                token.refreshExpiresAt());
    }
}

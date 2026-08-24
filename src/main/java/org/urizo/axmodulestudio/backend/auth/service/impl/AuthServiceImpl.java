package org.urizo.axmodulestudio.backend.auth.service.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.urizo.axmodulestudio.backend.auth.entity.AdminAccountEntity;
import org.urizo.axmodulestudio.backend.auth.entity.AdminSessionEntity;
import org.urizo.axmodulestudio.backend.auth.repository.AdminAccountRepository;
import org.urizo.axmodulestudio.backend.auth.repository.AdminSessionRepository;
import org.urizo.axmodulestudio.backend.auth.service.AuthService;
import org.urizo.axmodulestudio.backend.auth.service.AuthenticationFailedException;
import org.urizo.axmodulestudio.backend.auth.security.AuthenticatedActor;
import org.urizo.axmodulestudio.backend.auth.security.JwtTokenProvider;

@Service
@Profile("local-full")
public class AuthServiceImpl implements AuthService {

    private static final String TRANSACTION_MANAGER = "authJpaTransactionManager";

    private final AdminAccountRepository accounts;
    private final AdminSessionRepository sessions;
    private final PasswordHasher passwordHasher;
    private final JwtTokenProvider tokenProvider;
    private final Clock clock;

    public AuthServiceImpl(
            AdminAccountRepository accounts,
            AdminSessionRepository sessions,
            PasswordHasher passwordHasher,
            JwtTokenProvider tokenProvider,
            Clock clock) {
        this.accounts = accounts;
        this.sessions = sessions;
        this.passwordHasher = passwordHasher;
        this.tokenProvider = tokenProvider;
        this.clock = clock;
    }

    @Override
    @Transactional(transactionManager = TRANSACTION_MANAGER)
    public IssuedSession login(String loginId, char[] password) {
        if (password == null) {
            throw new AuthenticationFailedException("Invalid credentials.");
        }
        try {
            AdminAccountRepository.LoginCredential credential = loginId == null || loginId.isBlank()
                    ? null
                    : accounts.findCredentialByLoginId(loginId).orElse(null);
            boolean matches = credential == null
                    ? probeAbsentAccount(password)
                    : passwordHasher.matches(password, credential.getPasswordHash());
            if (credential == null || !matches) {
                throw new AuthenticationFailedException("Invalid credentials.");
            }
            AdminAccountEntity account = accounts.findByIdForUpdate(credential.getAccountId())
                    .filter(AdminAccountEntity::canAuthenticate)
                    .orElseThrow(() -> new AuthenticationFailedException("Invalid credentials."));
            return issue(account, null);
        }
        finally {
            Arrays.fill(password, '\0');
        }
    }

    @Override
    @Transactional(transactionManager = TRANSACTION_MANAGER)
    public IssuedSession refresh(String refreshToken) {
        JwtTokenProvider.RefreshIdentity identity = tokenProvider.decodeRefresh(refreshToken);
        // All state-changing auth flows lock account before session. Disable uses the same order.
        AdminAccountEntity account = lockedActiveAccount(identity.accountId());
        AdminSessionEntity current = sessions.findByJwtIdForUpdate(identity.jwtId())
                .orElseThrow(() -> new AuthenticationFailedException(
                        "A valid refresh token is required."));
        Instant now = Instant.now(clock);
        if (!current.getAccountId().equals(account.getAccountId())
                || !current.isUsableAt(now)
                || !constantTimeEquals(
                        current.getTokenDigest(), passwordHasher.digestToken(refreshToken))) {
            throw new AuthenticationFailedException("A valid refresh token is required.");
        }
        return issue(account, current);
    }

    @Override
    @Transactional(transactionManager = TRANSACTION_MANAGER)
    public void logout(UUID authenticatedAccountId, String refreshToken) {
        if (authenticatedAccountId == null) {
            throw new AuthenticationFailedException(
                    "A valid administrator session is required.");
        }
        if (refreshToken == null || refreshToken.isBlank()) {
            return;
        }
        try {
            JwtTokenProvider.RefreshIdentity identity = tokenProvider.decodeRefresh(refreshToken);
            if (!authenticatedAccountId.equals(identity.accountId())) {
                return;
            }
            sessions.findByJwtIdForUpdate(identity.jwtId()).ifPresent(session -> {
                String digest = passwordHasher.digestToken(refreshToken);
                if (session.getAccountId().equals(identity.accountId())
                        && constantTimeEquals(session.getTokenDigest(), digest)) {
                    session.revoke(Instant.now(clock));
                }
            });
        }
        catch (AuthenticationFailedException ex) {
            // Logout is idempotent and always allows the browser to clear its cookie.
        }
    }

    @Override
    @Transactional(transactionManager = TRANSACTION_MANAGER, readOnly = true)
    public AuthenticatedActor loadActor(UUID accountId) {
        return actor(activeAccount(accountId));
    }

    private IssuedSession issue(AdminAccountEntity account, AdminSessionEntity current) {
        AuthenticatedActor actor = actor(account);
        JwtTokenProvider.IssuedJwtPair tokens = tokenProvider.issue(actor);
        AdminSessionEntity replacement = new AdminSessionEntity(
                UUID.randomUUID(),
                account.getAccountId(),
                tokens.refreshJwtId(),
                passwordHasher.digestToken(tokens.refreshToken()),
                tokens.issuedAt(),
                tokens.refreshExpiresAt());
        if (current != null) {
            current.rotate(Instant.now(clock), replacement.getJwtId());
        }
        sessions.save(replacement);
        return new IssuedSession(
                tokens.accessToken(), tokens.accessExpiresAt(),
                tokens.refreshToken(), tokens.refreshExpiresAt(), actor);
    }

    private AdminAccountEntity activeAccount(UUID accountId) {
        return accounts.findById(accountId)
                .filter(AdminAccountEntity::canAuthenticate)
                .orElseThrow(() -> new AuthenticationFailedException(
                        "A valid administrator session is required."));
    }

    private AdminAccountEntity lockedActiveAccount(UUID accountId) {
        return accounts.findByIdForUpdate(accountId)
                .filter(AdminAccountEntity::canAuthenticate)
                .orElseThrow(() -> new AuthenticationFailedException(
                        "A valid administrator session is required."));
    }

    private AuthenticatedActor actor(AdminAccountEntity account) {
        return new AuthenticatedActor(
                account.getAccountId(), account.getDisplayName(), account.getRole());
    }

    private boolean probeAbsentAccount(char[] password) {
        passwordHasher.matches(password, passwordHasher.absentAccountProbe());
        return false;
    }

    private static boolean constantTimeEquals(String left, String right) {
        return left != null && right != null && MessageDigest.isEqual(
                left.getBytes(StandardCharsets.US_ASCII),
                right.getBytes(StandardCharsets.US_ASCII));
    }
}

package org.urizo.axmodulestudio.backend.common.auth;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Production login, logout, and session resolution.
 *
 * <p>Authority is always derived here from the persisted account and membership. A client-supplied
 * actor id, role, or Project claim never reaches {@link ActorContext}.
 */
public class AuthenticationService {

    private static final int TOKEN_BYTES = 32;

    private final AuthOperations operations;
    private final PasswordHasher hasher;
    private final Clock clock;
    private final AuthProperties properties;
    private final SecureRandom random = new SecureRandom();

    public AuthenticationService(
            AuthOperations operations,
            PasswordHasher hasher,
            Clock clock,
            AuthProperties properties) {
        this.operations = operations;
        this.hasher = hasher;
        this.clock = clock;
        this.properties = properties;
    }

    /**
     * Authenticates a credential pair and issues an opaque session.
     *
     * <p>The supplied password array is cleared before returning, on both the success and the
     * failure path.
     *
     * @throws AuthenticationFailedException for an unknown login id, a wrong password, or a disabled
     *     account
     */
    public IssuedSession login(String loginId, char[] password) {
        if (password == null) {
            throw new AuthenticationFailedException("Invalid credentials.");
        }
        try {
            Optional<AdminAccount> found = loginId == null || loginId.isBlank()
                    ? Optional.empty()
                    : operations.findAccountByLoginId(loginId);
            AdminAccount account = found.orElse(null);
            boolean verified = account == null
                    ? probeAbsentAccount(password)
                    : hasher.matches(password, account.passwordHash());
            if (account == null || !verified || !account.canAuthenticate()) {
                throw new AuthenticationFailedException("Invalid credentials.");
            }
            return issue(account);
        }
        finally {
            Arrays.fill(password, '\0');
        }
    }

    /** Revokes the session behind a presented token; an unknown token is silently accepted. */
    public void logout(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        operations.findSessionByTokenDigest(hasher.digestToken(token))
                .filter(session -> session.revokedAt() == null)
                .ifPresent(session -> operations.revokeSession(session.sessionId(), now()));
    }

    /**
     * Resolves the server-derived authority behind a presented token.
     *
     * @throws AuthenticationFailedException when the session or its account is unusable
     */
    public ActorContext resolve(String token) {
        return resolveSession(token).actor();
    }

    /**
     * Resolves the authority together with the session expiry.
     *
     * <p>A caller that reports the expiry uses this form so the session is read once instead of
     * twice.
     *
     * @throws AuthenticationFailedException when the session or its account is unusable
     */
    public SessionIdentity resolveSession(String token) {
        if (token == null || token.isBlank()) {
            throw new AuthenticationFailedException("A valid session is required.");
        }
        Instant now = now();
        AuthSession session = operations.findSessionByTokenDigest(hasher.digestToken(token))
                .filter(candidate -> candidate.isUsableAt(now))
                .orElseThrow(() -> new AuthenticationFailedException("A valid session is required."));
        AdminAccount account = operations.findAccountById(session.accountId())
                .filter(AdminAccount::canAuthenticate)
                .orElseThrow(() -> new AuthenticationFailedException("A valid session is required."));
        return new SessionIdentity(contextFor(account), session.expiresAt());
    }

    private IssuedSession issue(AdminAccount account) {
        byte[] entropy = new byte[TOKEN_BYTES];
        random.nextBytes(entropy);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
        Arrays.fill(entropy, (byte) 0);

        Instant issuedAt = now();
        Instant expiresAt = issuedAt.plus(properties.sessionLifetime());
        operations.createSession(new AuthSession(
                UUID.randomUUID(),
                account.accountId(),
                hasher.digestToken(token),
                issuedAt,
                expiresAt,
                null));
        return new IssuedSession(token, expiresAt, contextFor(account));
    }

    private ActorContext contextFor(AdminAccount account) {
        Set<UUID> projects = account.role().isPlatformGlobal()
                ? Set.of()
                : operations.findAssignedProjectIds(account.accountId());
        return new ActorContext(account.accountId(), account.role(), projects);
    }

    /**
     * Spends the same derivation cost as a real verification, so an unknown login id and a wrong
     * password are indistinguishable by response time.
     */
    private boolean probeAbsentAccount(char[] password) {
        hasher.matches(password, hasher.absentAccountProbe());
        return false;
    }

    private Instant now() {
        return Instant.now(clock);
    }
}

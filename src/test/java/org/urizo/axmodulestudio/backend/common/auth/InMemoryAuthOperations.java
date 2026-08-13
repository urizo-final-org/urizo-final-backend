package org.urizo.axmodulestudio.backend.common.auth;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Test double standing in for the JDBC adapter that arrives with the identity/RBAC revision. */
final class InMemoryAuthOperations implements AuthOperations {

    private final Map<UUID, AdminAccount> accounts = new HashMap<>();
    private final Map<UUID, AuthSession> sessions = new HashMap<>();
    private final Map<UUID, Set<UUID>> memberships = new HashMap<>();

    void save(AdminAccount account) {
        accounts.put(account.accountId(), account);
    }

    void assign(UUID accountId, UUID projectId) {
        memberships.computeIfAbsent(accountId, key -> new HashSet<>()).add(projectId);
    }

    int sessionCount() {
        return sessions.size();
    }

    @Override
    public Optional<AdminAccount> findAccountByLoginId(String loginId) {
        return accounts.values().stream()
                .filter(account -> account.loginId().equals(loginId))
                .findFirst();
    }

    @Override
    public Optional<AdminAccount> findAccountById(UUID accountId) {
        return Optional.ofNullable(accounts.get(accountId));
    }

    @Override
    public Optional<AuthSession> findSessionByTokenDigest(String tokenDigest) {
        return sessions.values().stream()
                .filter(session -> session.tokenDigest().equals(tokenDigest))
                .findFirst();
    }

    @Override
    public Set<UUID> findAssignedProjectIds(UUID accountId) {
        return Set.copyOf(memberships.getOrDefault(accountId, Set.of()));
    }

    @Override
    public void createSession(AuthSession session) {
        sessions.put(session.sessionId(), session);
    }

    @Override
    public void revokeSession(UUID sessionId, Instant revokedAt) {
        sessions.computeIfPresent(sessionId, (key, session) -> session.revokedAt() == null
                ? new AuthSession(
                        session.sessionId(), session.accountId(), session.tokenDigest(),
                        session.issuedAt(), session.expiresAt(), revokedAt)
                : session);
    }

    @Override
    public boolean existsByLoginId(String loginId) {
        return findAccountByLoginId(loginId).isPresent();
    }

    @Override
    public void createAccount(AdminAccount account) {
        if (existsByLoginId(account.loginId())) {
            throw new IllegalStateException("The unique login id constraint rejects this row.");
        }
        accounts.put(account.accountId(), account);
    }

    @Override
    public void updateAccountStatus(UUID accountId, AccountStatus status) {
        accounts.computeIfPresent(accountId, (key, account) -> new AdminAccount(
                account.accountId(), account.loginId(), account.passwordHash(), account.role(),
                status, account.createdAt()));
    }

    @Override
    public void revokeAccountSessions(UUID accountId, Instant revokedAt) {
        sessions.values().stream()
                .filter(session -> session.accountId().equals(accountId))
                .filter(session -> session.revokedAt() == null)
                .map(AuthSession::sessionId)
                .toList()
                .forEach(sessionId -> revokeSession(sessionId, revokedAt));
    }

    @Override
    public void addMembership(ProjectMembership membership) {
        memberships.computeIfAbsent(membership.accountId(), key -> new HashSet<>())
                .add(membership.projectId());
    }

    @Override
    public void removeMembership(UUID accountId, UUID projectId) {
        Set<UUID> assigned = memberships.get(accountId);
        if (assigned != null) {
            assigned.remove(projectId);
        }
    }
}

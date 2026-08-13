package org.urizo.axmodulestudio.backend.common.auth;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Persistence boundary required by production authentication.
 *
 * <p>The JDBC implementation arrives with the identity/RBAC Flyway revision. Keeping the boundary
 * explicit lets the authentication rules be verified without a database, in the same way the Stage 3
 * domains separate their {@code Operations} port from their {@code Store} adapter.
 */
public interface AuthOperations {

    Optional<AdminAccount> findAccountByLoginId(String loginId);

    Optional<AdminAccount> findAccountById(UUID accountId);

    Optional<AuthSession> findSessionByTokenDigest(String tokenDigest);

    /** Project ids assigned to a {@code GENERAL_ADMIN}; empty for a platform-global role. */
    Set<UUID> findAssignedProjectIds(UUID accountId);

    void createSession(AuthSession session);

    /** Marks one session revoked; a already revoked session keeps its original timestamp. */
    void revokeSession(UUID sessionId, Instant revokedAt);

    boolean existsByLoginId(String loginId);

    void createAccount(AdminAccount account);

    void updateAccountStatus(UUID accountId, AccountStatus status);

    /** Revokes every live session of one account, so disabling it takes effect immediately. */
    void revokeAccountSessions(UUID accountId, Instant revokedAt);

    void addMembership(ProjectMembership membership);

    void removeMembership(UUID accountId, UUID projectId);
}

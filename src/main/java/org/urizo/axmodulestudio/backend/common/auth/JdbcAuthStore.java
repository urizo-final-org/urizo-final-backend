package org.urizo.axmodulestudio.backend.common.auth;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Identity and Project-membership persistence on the Core DB.
 *
 * <p>Reads never widen beyond the columns the caller needs, and no method returns a password hash or
 * session digest to a layer that would serialize it.
 *
 * <p>Every write runs inside a transaction. The product pool hands out connections with auto-commit
 * disabled, so a bare update is discarded when the connection returns to the pool rather than
 * failing, which would let a bootstrap or a login report success while persisting nothing.
 */
public class JdbcAuthStore implements AuthOperations {

    private static final String ACCOUNT_COLUMNS =
            "account_id, login_id, password_hash, role, status, created_at";
    private static final String SESSION_COLUMNS =
            "session_id, account_id, token_digest, issued_at, expires_at, revoked_at";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    JdbcAuthStore(JdbcTemplate productJdbcTemplate, TransactionTemplate productTransactionTemplate) {
        this.jdbc = productJdbcTemplate;
        this.transactions = productTransactionTemplate;
    }

    @Override
    public Optional<AdminAccount> findAccountByLoginId(String loginId) {
        return first(jdbc.query(
                "SELECT " + ACCOUNT_COLUMNS + " FROM app.admin_account WHERE login_id = ?",
                (rs, row) -> account(rs), loginId));
    }

    @Override
    public Optional<AdminAccount> findAccountById(UUID accountId) {
        return first(jdbc.query(
                "SELECT " + ACCOUNT_COLUMNS + " FROM app.admin_account WHERE account_id = ?",
                (rs, row) -> account(rs), accountId));
    }

    @Override
    public Optional<AuthSession> findSessionByTokenDigest(String tokenDigest) {
        return first(jdbc.query(
                "SELECT " + SESSION_COLUMNS + " FROM app.admin_session WHERE token_digest = ?",
                (rs, row) -> session(rs), tokenDigest));
    }

    @Override
    public Set<UUID> findAssignedProjectIds(UUID accountId) {
        return jdbc.query(
                "SELECT project_id FROM app.project_membership WHERE account_id = ?",
                (rs, row) -> rs.getObject("project_id", UUID.class), accountId)
                .stream()
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public void createSession(AuthSession session) {
        transactions.executeWithoutResult(transaction ->
                jdbc.update(
                        "INSERT INTO app.admin_session "
                                + "(session_id, account_id, token_digest, issued_at, expires_at, revoked_at) "
                                + "VALUES (?, ?, ?, ?, ?, ?)",
                        session.sessionId(), session.accountId(), session.tokenDigest(),
                        Timestamp.from(session.issuedAt()), Timestamp.from(session.expiresAt()),
                        timestamp(session.revokedAt())));
    }

    @Override
    public void revokeSession(UUID sessionId, Instant revokedAt) {
        // An already revoked session keeps its original timestamp.
        transactions.executeWithoutResult(transaction ->
                jdbc.update(
                        "UPDATE app.admin_session SET revoked_at = ? "
                                + "WHERE session_id = ? AND revoked_at IS NULL",
                        Timestamp.from(revokedAt), sessionId));
    }

    @Override
    public boolean existsByLoginId(String loginId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM app.admin_account WHERE login_id = ?", Integer.class, loginId);
        return count != null && count > 0;
    }

    @Override
    public void createAccount(AdminAccount account) {
        transactions.executeWithoutResult(transaction ->
                jdbc.update(
                        "INSERT INTO app.admin_account "
                                + "(account_id, login_id, password_hash, role, status, created_at, updated_at) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                        account.accountId(), account.loginId(), account.passwordHash(),
                        account.role().name(), account.status().name(),
                        Timestamp.from(account.createdAt()), Timestamp.from(account.createdAt())));
    }

    @Override
    public void updateAccountStatus(UUID accountId, AccountStatus status) {
        transactions.executeWithoutResult(transaction ->
                jdbc.update(
                        "UPDATE app.admin_account SET status = ?, updated_at = CURRENT_TIMESTAMP "
                                + "WHERE account_id = ?",
                        status.name(), accountId));
    }

    @Override
    public void revokeAccountSessions(UUID accountId, Instant revokedAt) {
        transactions.executeWithoutResult(transaction ->
                jdbc.update(
                        "UPDATE app.admin_session SET revoked_at = ? "
                                + "WHERE account_id = ? AND revoked_at IS NULL",
                        Timestamp.from(revokedAt), accountId));
    }

    @Override
    public void addMembership(ProjectMembership membership) {
        // Re-assigning an existing membership keeps the original assignment time.
        transactions.executeWithoutResult(transaction ->
                jdbc.update(
                        "INSERT INTO app.project_membership (account_id, project_id, assigned_at) "
                                + "VALUES (?, ?, ?) ON CONFLICT (account_id, project_id) DO NOTHING",
                        membership.accountId(), membership.projectId(),
                        Timestamp.from(membership.assignedAt())));
    }

    @Override
    public void removeMembership(UUID accountId, UUID projectId) {
        transactions.executeWithoutResult(transaction ->
                jdbc.update(
                        "DELETE FROM app.project_membership WHERE account_id = ? AND project_id = ?",
                        accountId, projectId));
    }

    private static AdminAccount account(ResultSet rs) throws SQLException {
        return new AdminAccount(
                rs.getObject("account_id", UUID.class),
                rs.getString("login_id"),
                rs.getString("password_hash"),
                AdminRole.from(rs.getString("role")),
                AccountStatus.from(rs.getString("status")),
                rs.getTimestamp("created_at").toInstant());
    }

    private static AuthSession session(ResultSet rs) throws SQLException {
        Timestamp revokedAt = rs.getTimestamp("revoked_at");
        return new AuthSession(
                rs.getObject("session_id", UUID.class),
                rs.getObject("account_id", UUID.class),
                rs.getString("token_digest"),
                rs.getTimestamp("issued_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant(),
                revokedAt == null ? null : revokedAt.toInstant());
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static <T> Optional<T> first(List<T> rows) {
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }
}

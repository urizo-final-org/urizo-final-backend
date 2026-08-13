package org.urizo.axmodulestudio.backend.common.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Verifies identity/RBAC persistence against the real Core DB schema.
 *
 * <p>The suite connects as the runtime role through the loopback gateway instead of starting the
 * container-only application context, and it asserts the database constraints as well: the
 * application is not the only writer that could reach these tables.
 *
 * <p>The pool mirrors the deployed one by disabling auto-commit. With auto-commit left on, a write
 * that forgot its transaction would still appear to succeed here while being silently discarded in
 * the running service.
 *
 * <p>Enable with {@code AXMS_RUN_AUTH_DB_INTEGRATION=true} while the local-full stack is healthy.
 */
@EnabledIfEnvironmentVariable(named = "AXMS_RUN_AUTH_DB_INTEGRATION", matches = "true")
class JdbcAuthStoreIntegrationTest {

    private static final PasswordHasher HASHER = new PasswordHasher(1_000);
    private static final String JDBC_URL = "jdbc:postgresql://127.0.0.1:15432/ax_module_studio";

    private static JdbcTemplate jdbc;
    private static TransactionTemplate transactions;

    private final JdbcAuthStore store = new JdbcAuthStore(jdbc, transactions);
    private final UUID accountId = UUID.randomUUID();

    private UUID projectId;

    @BeforeAll
    static void connect() throws Exception {
        String password = Files.readString(
                Path.of(".local", "secrets", "cms_app_password"), StandardCharsets.UTF_8).trim();
        HikariConfig config = new HikariConfig();
        config.setPoolName("axms-auth-integration-pool");
        config.setJdbcUrl(JDBC_URL);
        config.setUsername("cms_app");
        config.setPassword(password);
        config.setMaximumPoolSize(2);
        config.setAutoCommit(false);
        HikariDataSource dataSource = new HikariDataSource(config);
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @AfterEach
    void cleanUp() {
        transactions.executeWithoutResult(transaction -> {
            jdbc.update("DELETE FROM app.admin_session WHERE account_id = ?", accountId);
            jdbc.update("DELETE FROM app.project_membership WHERE account_id = ?", accountId);
            jdbc.update("DELETE FROM app.admin_account WHERE account_id = ?", accountId);
            if (projectId != null) {
                jdbc.update("DELETE FROM app.project WHERE project_id = ?", projectId);
            }
        });
    }

    @Test
    void storesAndReadsBackAnAdministratorAccount() {
        AdminAccount account = account(AdminRole.GENERAL_ADMIN, AccountStatus.ACTIVE);

        store.createAccount(account);

        assertThat(store.existsByLoginId(account.loginId())).isTrue();
        assertThat(store.findAccountByLoginId(account.loginId())).contains(account);
        assertThat(store.findAccountById(accountId)).contains(account);
    }

    @Test
    void rejectsADuplicateLoginIdAtTheDatabase() {
        AdminAccount account = account(AdminRole.GENERAL_ADMIN, AccountStatus.ACTIVE);
        store.createAccount(account);

        AdminAccount duplicate = new AdminAccount(
                UUID.randomUUID(), account.loginId(), account.passwordHash(), account.role(),
                account.status(), account.createdAt());

        assertThatThrownBy(() -> store.createAccount(duplicate))
                .as("the unique login id constraint must reject the second row")
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void refusesAPasswordValueThatIsNotAVersionedHash() {
        AdminAccount plaintext = new AdminAccount(
                accountId, loginId(), "not-a-hash", AdminRole.GENERAL_ADMIN,
                AccountStatus.ACTIVE, now());

        assertThatThrownBy(() -> store.createAccount(plaintext))
                .as("the database must reject a plaintext or foreign password value")
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void refusesARoleOutsideTheTwoFixedMvpRoles() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO app.admin_account "
                        + "(account_id, login_id, password_hash, role, status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'REVIEWER', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                accountId, loginId(), HASHER.hash("value".toCharArray())))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void issuesAndRevokesASessionWithoutStoringTheToken() {
        store.createAccount(account(AdminRole.GENERAL_ADMIN, AccountStatus.ACTIVE));
        String token = UUID.randomUUID().toString();
        AuthSession session = session(HASHER.digestToken(token));

        store.createSession(session);

        assertThat(store.findSessionByTokenDigest(token))
                .as("the presented token must never match a stored row")
                .isEmpty();
        assertThat(store.findSessionByTokenDigest(session.tokenDigest()))
                .hasValueSatisfying(stored -> {
                    assertThat(stored.accountId()).isEqualTo(accountId);
                    assertThat(stored.revokedAt()).isNull();
                });

        Instant revokedAt = now();
        store.revokeSession(session.sessionId(), revokedAt);
        store.revokeSession(session.sessionId(), revokedAt.plusSeconds(60));

        assertThat(store.findSessionByTokenDigest(session.tokenDigest()))
                .hasValueSatisfying(stored -> {
                    assertThat(stored.revokedAt())
                            .as("a second revocation keeps the original timestamp")
                            .isEqualTo(revokedAt);
                    assertThat(stored.isUsableAt(now())).isFalse();
                });
    }

    @Test
    void revokesEverySessionWhenAnAccountIsDisabled() {
        store.createAccount(account(AdminRole.GENERAL_ADMIN, AccountStatus.ACTIVE));
        AuthSession first = session(HASHER.digestToken(UUID.randomUUID().toString()));
        AuthSession second = session(HASHER.digestToken(UUID.randomUUID().toString()));
        store.createSession(first);
        store.createSession(second);

        store.updateAccountStatus(accountId, AccountStatus.DISABLED);
        store.revokeAccountSessions(accountId, now());

        assertThat(store.findAccountById(accountId))
                .hasValueSatisfying(stored -> assertThat(stored.canAuthenticate()).isFalse());
        assertThat(store.findSessionByTokenDigest(first.tokenDigest()))
                .hasValueSatisfying(stored -> assertThat(stored.revokedAt()).isNotNull());
        assertThat(store.findSessionByTokenDigest(second.tokenDigest()))
                .hasValueSatisfying(stored -> assertThat(stored.revokedAt()).isNotNull());
    }

    @Test
    void assignsAndRemovesProjectMembershipIdempotently() {
        store.createAccount(account(AdminRole.GENERAL_ADMIN, AccountStatus.ACTIVE));
        projectId = createProject();
        ProjectMembership membership = new ProjectMembership(accountId, projectId, now());

        store.addMembership(membership);
        store.addMembership(membership);

        assertThat(store.findAssignedProjectIds(accountId)).containsExactly(projectId);

        store.removeMembership(accountId, projectId);
        store.removeMembership(accountId, projectId);

        assertThat(store.findAssignedProjectIds(accountId)).isEmpty();
    }

    @Test
    void reportsNothingForAnUnknownAccountOrSession() {
        assertThat(store.findAccountByLoginId("absent-" + UUID.randomUUID())).isEmpty();
        assertThat(store.findAccountById(UUID.randomUUID())).isEmpty();
        assertThat(store.findSessionByTokenDigest("absent-digest")).isEmpty();
        assertThat(store.findAssignedProjectIds(UUID.randomUUID())).isEmpty();
    }

    private AdminAccount account(AdminRole role, AccountStatus status) {
        return new AdminAccount(
                accountId, loginId(), HASHER.hash("integration-password".toCharArray()), role,
                status, now());
    }

    private AuthSession session(String digest) {
        Instant issuedAt = now();
        return new AuthSession(
                UUID.randomUUID(), accountId, digest, issuedAt, issuedAt.plusSeconds(3600), null);
    }

    private UUID createProject() {
        UUID created = UUID.randomUUID();
        Timestamp createdAt = Timestamp.from(now());
        transactions.executeWithoutResult(transaction ->
                jdbc.update(
                        "INSERT INTO app.project (project_id, name, status, created_at, updated_at) "
                                + "VALUES (?, ?, 'ACTIVE', ?, ?)",
                        created, "auth-it-" + created, createdAt, createdAt));
        return created;
    }

    private static String loginId() {
        return "it-" + UUID.randomUUID();
    }

    /** The columns are {@code timestamptz}, so microsecond truncation keeps read-back equality. */
    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}

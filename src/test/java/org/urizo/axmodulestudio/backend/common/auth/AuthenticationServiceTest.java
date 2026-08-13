package org.urizo.axmodulestudio.backend.common.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AuthenticationServiceTest {

    private static final Instant START = Instant.parse("2026-08-13T00:00:00Z");
    private static final Duration LIFETIME = Duration.ofHours(8);
    private static final String PASSWORD = "correct-horse-battery";

    private final InMemoryAuthOperations operations = new InMemoryAuthOperations();
    private final PasswordHasher hasher = new PasswordHasher(1_000);
    private final MutableClock clock = new MutableClock(START);

    private AuthenticationService service;
    private AdminAccount superAdmin;
    private AdminAccount generalAdmin;
    private UUID assignedProject;

    @BeforeEach
    void setUp() {
        service = new AuthenticationService(operations, hasher, clock, new AuthProperties(LIFETIME));
        superAdmin = account("root", AdminRole.SUPER_ADMIN, AccountStatus.ACTIVE);
        generalAdmin = account("customer", AdminRole.GENERAL_ADMIN, AccountStatus.ACTIVE);
        assignedProject = UUID.randomUUID();
        operations.save(superAdmin);
        operations.save(generalAdmin);
        operations.assign(generalAdmin.accountId(), assignedProject);
    }

    @Test
    void issuesASessionCarryingServerDerivedAuthorityForBothRoles() {
        IssuedSession issued = service.login("customer", PASSWORD.toCharArray());

        assertThat(issued.token()).isNotBlank();
        assertThat(issued.expiresAt()).isEqualTo(START.plus(LIFETIME));
        assertThat(issued.actor().actorId()).isEqualTo(generalAdmin.accountId());
        assertThat(issued.actor().role()).isEqualTo(AdminRole.GENERAL_ADMIN);
        assertThat(issued.actor().assignedProjectIds()).containsExactly(assignedProject);

        ActorContext root = service.login("root", PASSWORD.toCharArray()).actor();
        assertThat(root.role()).isEqualTo(AdminRole.SUPER_ADMIN);
        assertThat(root.canAccessProject(UUID.randomUUID())).isTrue();
    }

    @Test
    void neverPersistsTheOpaqueTokenItself() {
        IssuedSession issued = service.login("customer", PASSWORD.toCharArray());

        assertThat(operations.findSessionByTokenDigest(issued.token())).isEmpty();
        assertThat(operations.findSessionByTokenDigest(hasher.digestToken(issued.token())))
                .isPresent();
    }

    @Test
    void issuesADistinctTokenForEveryLogin() {
        String first = service.login("customer", PASSWORD.toCharArray()).token();
        String second = service.login("customer", PASSWORD.toCharArray()).token();

        assertThat(first).isNotEqualTo(second);
        assertThat(operations.sessionCount()).isEqualTo(2);
    }

    @Test
    void rejectsAnUnknownLoginIdAndAWrongPasswordIdentically() {
        assertThatThrownBy(() -> service.login("absent", PASSWORD.toCharArray()))
                .isInstanceOf(AuthenticationFailedException.class)
                .hasMessage("Invalid credentials.");
        assertThatThrownBy(() -> service.login("customer", "wrong".toCharArray()))
                .isInstanceOf(AuthenticationFailedException.class)
                .hasMessage("Invalid credentials.");

        assertThat(operations.sessionCount()).isZero();
    }

    @Test
    void refusesADisabledAccountEvenWithTheCorrectPassword() {
        operations.save(disabled(generalAdmin));

        assertThatThrownBy(() -> service.login("customer", PASSWORD.toCharArray()))
                .isInstanceOf(AuthenticationFailedException.class);
        assertThat(operations.sessionCount()).isZero();
    }

    @Test
    void clearsTheSuppliedPasswordOnBothTheSuccessAndTheFailurePath() {
        char[] onSuccess = PASSWORD.toCharArray();
        service.login("customer", onSuccess);
        assertThat(onSuccess).containsOnly('\0');

        char[] onFailure = "wrong".toCharArray();
        assertThatThrownBy(() -> service.login("customer", onFailure))
                .isInstanceOf(AuthenticationFailedException.class);
        assertThat(onFailure).containsOnly('\0');
    }

    @Test
    void resolvesAuthorityFromTheStoredSessionRatherThanTheRequest() {
        String token = service.login("customer", PASSWORD.toCharArray()).token();

        ActorContext actor = service.resolve(token);

        assertThat(actor.actorId()).isEqualTo(generalAdmin.accountId());
        assertThat(actor.canAccessProject(assignedProject)).isTrue();
        assertThat(actor.canAccessProject(UUID.randomUUID())).isFalse();
        assertThat(actor.canConfigurePlatform()).isFalse();
    }

    @Test
    void failsClosedForAnExpiredSession() {
        String token = service.login("customer", PASSWORD.toCharArray()).token();

        clock.advance(LIFETIME);

        assertThatThrownBy(() -> service.resolve(token))
                .isInstanceOf(AuthenticationFailedException.class);
    }

    @Test
    void failsClosedAfterLogout() {
        String token = service.login("customer", PASSWORD.toCharArray()).token();

        service.logout(token);

        assertThatThrownBy(() -> service.resolve(token))
                .isInstanceOf(AuthenticationFailedException.class);
    }

    @Test
    void failsClosedWhenTheAccountIsDisabledAfterTheSessionWasIssued() {
        String token = service.login("customer", PASSWORD.toCharArray()).token();

        operations.save(disabled(generalAdmin));

        assertThatThrownBy(() -> service.resolve(token))
                .isInstanceOf(AuthenticationFailedException.class);
    }

    @Test
    void rejectsAMissingOrForgedToken() {
        assertThatThrownBy(() -> service.resolve(null))
                .isInstanceOf(AuthenticationFailedException.class);
        assertThatThrownBy(() -> service.resolve("  "))
                .isInstanceOf(AuthenticationFailedException.class);
        assertThatThrownBy(() -> service.resolve("forged-token-value"))
                .isInstanceOf(AuthenticationFailedException.class);
    }

    @Test
    void toleratesLogoutForAnAbsentOrAlreadyRevokedSession() {
        String token = service.login("customer", PASSWORD.toCharArray()).token();

        service.logout(null);
        service.logout("unknown-token");
        service.logout(token);
        service.logout(token);

        assertThatThrownBy(() -> service.resolve(token))
                .isInstanceOf(AuthenticationFailedException.class);
    }

    private AdminAccount account(String loginId, AdminRole role, AccountStatus status) {
        return new AdminAccount(
                UUID.randomUUID(), loginId, hasher.hash(PASSWORD.toCharArray()), role, status, START);
    }

    /** Same account id and credential, so the stored row is replaced rather than duplicated. */
    private static AdminAccount disabled(AdminAccount account) {
        return new AdminAccount(
                account.accountId(), account.loginId(), account.passwordHash(), account.role(),
                AccountStatus.DISABLED, account.createdAt());
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration amount) {
            instant = instant.plus(amount);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}

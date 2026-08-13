package org.urizo.axmodulestudio.backend.common.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AdminAccountServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-13T00:00:00Z");
    private static final String PASSWORD = "operator-password";

    private final InMemoryAuthOperations operations = new InMemoryAuthOperations();
    private final PasswordHasher hasher = new PasswordHasher(1_000);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final AuthorizationService authorization = new AuthorizationService();

    private AdminAccountService service;
    private AuthenticationService authentication;
    private ActorContext superAdmin;
    private ActorContext generalAdmin;
    private UUID projectId;

    @BeforeEach
    void setUp() {
        service = new AdminAccountService(operations, authorization, hasher, clock);
        authentication = new AuthenticationService(
                operations, hasher, clock, new AuthProperties(Duration.ofHours(8)));
        superAdmin = new ActorContext(UUID.randomUUID(), AdminRole.SUPER_ADMIN, Set.of());
        projectId = UUID.randomUUID();
        generalAdmin = new ActorContext(UUID.randomUUID(), AdminRole.GENERAL_ADMIN, Set.of(projectId));
    }

    @Test
    void createsAGeneralAdministratorThatCanImmediatelySignIn() {
        AdministratorSummary created = service.createAdministrator(
                superAdmin, "customer", PASSWORD.toCharArray(), AdminRole.GENERAL_ADMIN);

        assertThat(created.loginId()).isEqualTo("customer");
        assertThat(created.role()).isEqualTo(AdminRole.GENERAL_ADMIN);
        assertThat(created.status()).isEqualTo(AccountStatus.ACTIVE);

        IssuedSession session = authentication.login("customer", PASSWORD.toCharArray());
        assertThat(session.actor().actorId()).isEqualTo(created.accountId());
    }

    @Test
    void neverExposesCredentialMaterialInTheReturnedSummary() {
        AdministratorSummary created = service.createAdministrator(
                superAdmin, "customer", PASSWORD.toCharArray(), AdminRole.GENERAL_ADMIN);

        assertThat(created.toString())
                .doesNotContain(PASSWORD)
                .doesNotContain("pbkdf2");
    }

    @Test
    void refusesEveryAccountOperationForAGeneralAdministrator() {
        UUID target = service.createAdministrator(
                superAdmin, "target", PASSWORD.toCharArray(), AdminRole.GENERAL_ADMIN).accountId();

        assertThatThrownBy(() -> service.createAdministrator(
                generalAdmin, "another", PASSWORD.toCharArray(), AdminRole.GENERAL_ADMIN))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.disableAdministrator(generalAdmin, target))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.restoreAdministrator(generalAdmin, target))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.assignProject(generalAdmin, target, projectId))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.removeProject(generalAdmin, target, projectId))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void separatesTheSuperAdminGrantFromOrdinaryAccountAdministration() {
        assertThatCode(() -> service.createAdministrator(
                superAdmin, "second-root", PASSWORD.toCharArray(), AdminRole.SUPER_ADMIN))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> service.createAdministrator(
                generalAdmin, "escalated", PASSWORD.toCharArray(), AdminRole.SUPER_ADMIN))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void rejectsADuplicateLoginIdIncludingSurroundingWhitespace() {
        service.createAdministrator(
                superAdmin, "customer", PASSWORD.toCharArray(), AdminRole.GENERAL_ADMIN);

        assertThatThrownBy(() -> service.createAdministrator(
                superAdmin, "  customer  ", PASSWORD.toCharArray(), AdminRole.GENERAL_ADMIN))
                .isInstanceOf(AdminAccountConflictException.class);
    }

    @Test
    void rejectsAnAbsentLoginIdOrAnUnusablePassword() {
        assertThatThrownBy(() -> service.createAdministrator(
                superAdmin, "  ", PASSWORD.toCharArray(), AdminRole.GENERAL_ADMIN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.createAdministrator(
                superAdmin, "short", "1234567".toCharArray(), AdminRole.GENERAL_ADMIN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void clearsTheSuppliedPasswordEvenWhenCreationFails() {
        char[] denied = PASSWORD.toCharArray();

        assertThatThrownBy(() -> service.createAdministrator(
                generalAdmin, "denied", denied, AdminRole.GENERAL_ADMIN))
                .isInstanceOf(AccessDeniedException.class);

        assertThat(denied).containsOnly('\0');
    }

    @Test
    void endsLiveSessionsWhenAnAccountIsDisabled() {
        service.createAdministrator(
                superAdmin, "customer", PASSWORD.toCharArray(), AdminRole.GENERAL_ADMIN);
        String token = authentication.login("customer", PASSWORD.toCharArray()).token();
        UUID accountId = authentication.resolve(token).actorId();

        AdministratorSummary disabled = service.disableAdministrator(superAdmin, accountId);

        assertThat(disabled.status()).isEqualTo(AccountStatus.DISABLED);
        assertThatThrownBy(() -> authentication.resolve(token))
                .as("an open session must not survive the account being closed")
                .isInstanceOf(AuthenticationFailedException.class);
        assertThatThrownBy(() -> authentication.login("customer", PASSWORD.toCharArray()))
                .isInstanceOf(AuthenticationFailedException.class);
    }

    @Test
    void requiresAFreshLoginAfterAnAccountIsRestored() {
        service.createAdministrator(
                superAdmin, "customer", PASSWORD.toCharArray(), AdminRole.GENERAL_ADMIN);
        String token = authentication.login("customer", PASSWORD.toCharArray()).token();
        UUID accountId = authentication.resolve(token).actorId();
        service.disableAdministrator(superAdmin, accountId);

        assertThat(service.restoreAdministrator(superAdmin, accountId).status())
                .isEqualTo(AccountStatus.ACTIVE);
        assertThatThrownBy(() -> authentication.resolve(token))
                .as("the revoked session stays revoked")
                .isInstanceOf(AuthenticationFailedException.class);
        assertThatCode(() -> authentication.login("customer", PASSWORD.toCharArray()))
                .doesNotThrowAnyException();
    }

    @Test
    void grantsProjectScopeThroughMembershipAndTakesItBack() {
        UUID accountId = service.createAdministrator(
                superAdmin, "customer", PASSWORD.toCharArray(), AdminRole.GENERAL_ADMIN).accountId();

        service.assignProject(superAdmin, accountId, projectId);
        String token = authentication.login("customer", PASSWORD.toCharArray()).token();
        assertThat(authentication.resolve(token).canAccessProject(projectId)).isTrue();

        service.removeProject(superAdmin, accountId, projectId);
        assertThat(authentication.resolve(token).canAccessProject(projectId))
                .as("scope is re-derived per request, so removal takes effect at once")
                .isFalse();
    }

    @Test
    void refusesToRecordMembershipForAPlatformGlobalAdministrator() {
        UUID rootId = service.createAdministrator(
                superAdmin, "second-root", PASSWORD.toCharArray(), AdminRole.SUPER_ADMIN).accountId();

        assertThatThrownBy(() -> service.assignProject(superAdmin, rootId, projectId))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reportsAnUnknownAccountAsNotFound() {
        UUID absent = UUID.randomUUID();

        assertThatThrownBy(() -> service.disableAdministrator(superAdmin, absent))
                .isInstanceOf(AdminAccountNotFoundException.class);
        assertThatThrownBy(() -> service.assignProject(superAdmin, absent, projectId))
                .isInstanceOf(AdminAccountNotFoundException.class);
    }

    @Test
    void toleratesRemovingAnAssignmentThatIsNotThere() {
        UUID accountId = service.createAdministrator(
                superAdmin, "customer", PASSWORD.toCharArray(), AdminRole.GENERAL_ADMIN).accountId();

        assertThatCode(() -> service.removeProject(superAdmin, accountId, projectId))
                .doesNotThrowAnyException();
    }
}

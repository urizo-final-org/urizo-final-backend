package org.urizo.axmodulestudio.backend.common.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class AuthBootstrapRunnerTest {

    private static final String SUPER_PASSWORD = "axms-super-admin-demo";
    private static final String GENERAL_PASSWORD = "axms-general-admin-demo";

    private final Clock clock = Clock.fixed(Instant.parse("2026-08-13T09:00:00Z"), ZoneOffset.UTC);
    private final PasswordHasher hasher = new PasswordHasher(1_000);
    private final InMemoryAuthOperations operations = new InMemoryAuthOperations();

    @Test
    void bothAdministratorsAreCreatedAndCanAuthenticate() {
        runner(properties(true)).run(null);

        AdminAccount superAdmin = account("super-admin");
        AdminAccount generalAdmin = account("general-admin");

        assertThat(superAdmin.role()).isEqualTo(AdminRole.SUPER_ADMIN);
        assertThat(generalAdmin.role()).isEqualTo(AdminRole.GENERAL_ADMIN);
        assertThat(superAdmin.status()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(hasher.matches(SUPER_PASSWORD.toCharArray(), superAdmin.passwordHash())).isTrue();
        assertThat(hasher.matches(GENERAL_PASSWORD.toCharArray(), generalAdmin.passwordHash()))
                .isTrue();
    }

    @Test
    void theStoredValueIsAHashRatherThanThePassword() {
        runner(properties(true)).run(null);

        assertThat(account("super-admin").passwordHash())
                .doesNotContain(SUPER_PASSWORD)
                .startsWith("pbkdf2-sha256$");
    }

    @Test
    void runningAgainLeavesAnExistingAccountUntouched() {
        runner(properties(true)).run(null);
        String originalHash = account("super-admin").passwordHash();

        runner(properties(true)).run(null);

        assertThat(account("super-admin").passwordHash()).isEqualTo(originalHash);
    }

    @Test
    void aRestartDoesNotReviveADisabledAccount() {
        runner(properties(true)).run(null);
        operations.updateAccountStatus(account("super-admin").accountId(), AccountStatus.DISABLED);

        runner(properties(true)).run(null);

        assertThat(account("super-admin").status()).isEqualTo(AccountStatus.DISABLED);
    }

    @Test
    void aDisabledBootstrapCreatesNothing() {
        runner(properties(false)).run(null);

        assertThat(operations.existsByLoginId("super-admin")).isFalse();
        assertThat(operations.existsByLoginId("general-admin")).isFalse();
    }

    @Test
    void aBlankPasswordLeavesTheAccountUncreatedRatherThanUnusable() {
        runner(new AuthBootstrapProperties(
                true, "super-admin", "", "general-admin", GENERAL_PASSWORD)).run(null);

        assertThat(operations.existsByLoginId("super-admin")).isFalse();
        assertThat(operations.existsByLoginId("general-admin")).isTrue();
    }

    @Test
    void aPasswordShorterThanTheMinimumIsRefused() {
        runner(new AuthBootstrapProperties(
                true, "super-admin", "short", "general-admin", GENERAL_PASSWORD)).run(null);

        assertThat(operations.existsByLoginId("super-admin")).isFalse();
    }

    private AuthBootstrapRunner runner(AuthBootstrapProperties properties) {
        return new AuthBootstrapRunner(operations, hasher, clock, properties);
    }

    private static AuthBootstrapProperties properties(boolean enabled) {
        return new AuthBootstrapProperties(
                enabled, "super-admin", SUPER_PASSWORD, "general-admin", GENERAL_PASSWORD);
    }

    private AdminAccount account(String loginId) {
        Optional<AdminAccount> found = operations.findAccountByLoginId(loginId);
        assertThat(found).as("account '%s'", loginId).isPresent();
        return found.orElseThrow();
    }
}

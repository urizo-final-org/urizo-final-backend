package org.urizo.axmodulestudio.backend.common.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class AuthDomainTest {

    private static final Instant NOW = Instant.parse("2026-08-13T00:00:00Z");

    @Test
    void resolvesTheLegacyCodingRoleStringWithoutRenamingTheConsumerContract() {
        assertThat(AdminRole.from("PROJECT_ADMIN")).isEqualTo(AdminRole.GENERAL_ADMIN);
        assertThat(AdminRole.from("general_admin")).isEqualTo(AdminRole.GENERAL_ADMIN);
        assertThat(AdminRole.from(" SUPER_ADMIN ")).isEqualTo(AdminRole.SUPER_ADMIN);
    }

    @Test
    void rejectsARoleValueOutsideTheTwoFixedMvpRoles() {
        assertThatThrownBy(() -> AdminRole.from("REVIEWER"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AdminRole.from(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void limitsPlatformGlobalScopeToSuperAdmin() {
        assertThat(AdminRole.SUPER_ADMIN.isPlatformGlobal()).isTrue();
        assertThat(AdminRole.GENERAL_ADMIN.isPlatformGlobal()).isFalse();
    }

    @Test
    void allowsAuthenticationOnlyForAnActiveAccount() {
        assertThat(AccountStatus.from("ACTIVE").canAuthenticate()).isTrue();
        assertThat(AccountStatus.DISABLED.canAuthenticate()).isFalse();
    }

    @Test
    void requiresEveryPersistedAccountFieldToBePresent() {
        assertThatThrownBy(() -> account("  ", "hash"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> account("admin", "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void failsClosedForRevokedAndExpiredSessions() {
        AuthSession live = session(NOW, NOW.plusSeconds(600), null);
        AuthSession revoked = session(NOW, NOW.plusSeconds(600), NOW.minusSeconds(1));
        AuthSession expired = session(NOW.minusSeconds(3600), NOW.minusSeconds(1), null);

        assertThat(live.isUsableAt(NOW)).isTrue();
        assertThat(revoked.isUsableAt(NOW)).isFalse();
        assertThat(expired.isUsableAt(NOW)).isFalse();
    }

    @Test
    void rejectsASessionThatExpiresBeforeItIsIssued() {
        assertThatThrownBy(() -> session(NOW, NOW, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void grantsEveryProjectToSuperAdminAndOnlyAssignedProjectsToGeneralAdmin() {
        UUID assigned = UUID.randomUUID();
        UUID other = UUID.randomUUID();

        ActorContext superAdmin = new ActorContext(UUID.randomUUID(), AdminRole.SUPER_ADMIN, Set.of());
        ActorContext generalAdmin =
                new ActorContext(UUID.randomUUID(), AdminRole.GENERAL_ADMIN, Set.of(assigned));

        assertThat(superAdmin.canAccessProject(other)).isTrue();
        assertThat(superAdmin.canConfigurePlatform()).isTrue();
        assertThat(generalAdmin.canAccessProject(assigned)).isTrue();
        assertThat(generalAdmin.canAccessProject(other)).isFalse();
        assertThat(generalAdmin.canConfigurePlatform()).isFalse();
    }

    @Test
    void keepsTheAssignedProjectSetImmutable() {
        ActorContext actor =
                new ActorContext(UUID.randomUUID(), AdminRole.GENERAL_ADMIN, Set.of(UUID.randomUUID()));
        assertThatThrownBy(() -> actor.assignedProjectIds().add(UUID.randomUUID()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static AdminAccount account(String loginId, String passwordHash) {
        return new AdminAccount(
                UUID.randomUUID(), loginId, passwordHash, AdminRole.GENERAL_ADMIN,
                AccountStatus.ACTIVE, NOW);
    }

    private static AuthSession session(Instant issuedAt, Instant expiresAt, Instant revokedAt) {
        return new AuthSession(
                UUID.randomUUID(), UUID.randomUUID(), "digest", issuedAt, expiresAt, revokedAt);
    }
}

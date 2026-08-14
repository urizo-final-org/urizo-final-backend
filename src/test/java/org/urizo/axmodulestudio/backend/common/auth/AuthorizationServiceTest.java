package org.urizo.axmodulestudio.backend.common.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class AuthorizationServiceTest {

    private final AuthorizationService authorization = new AuthorizationService();

    private final UUID assignedProject = UUID.randomUUID();
    private final UUID foreignProject = UUID.randomUUID();

    private final ActorContext superAdmin =
            new ActorContext(UUID.randomUUID(), AdminRole.SUPER_ADMIN, Set.of());
    private final ActorContext generalAdmin =
            new ActorContext(UUID.randomUUID(), AdminRole.GENERAL_ADMIN, Set.of(assignedProject));

    @Test
    void keepsEveryPlatformGlobalOperationInTheDeliveryCompanyLane() {
        Arrays.stream(AdminPermission.values())
                .filter(AdminPermission::isPlatformGlobal)
                .forEach(permission -> {
                    assertThatCode(() -> authorization.authorize(superAdmin, permission))
                            .doesNotThrowAnyException();
                    assertThatThrownBy(() -> authorization.authorize(generalAdmin, permission))
                            .as("%s must be denied for GENERAL_ADMIN", permission)
                            .isInstanceOf(AccessDeniedException.class);
                });
    }

    @Test
    void allowsEveryProjectScopedOperationOnAnAssignedProject() {
        Arrays.stream(AdminPermission.values())
                .filter(AdminPermission::isProjectScoped)
                .forEach(permission -> {
                    assertThatCode(
                            () -> authorization.authorize(generalAdmin, permission, assignedProject))
                            .as("%s must be allowed on an assigned Project", permission)
                            .doesNotThrowAnyException();
                    assertThatCode(
                            () -> authorization.authorize(superAdmin, permission, foreignProject))
                            .as("%s must stay available to the support override", permission)
                            .doesNotThrowAnyException();
                });
    }

    @Test
    void hidesAnUnassignedProjectBehindNotFoundRatherThanForbidden() {
        assertThatThrownBy(
                () -> authorization.authorize(generalAdmin, AdminPermission.BOARD_MANAGE, foreignProject))
                .isInstanceOf(ProjectNotVisibleException.class)
                .hasMessage("The requested resource does not exist.");
    }

    @Test
    void separatesForbiddenFromNotFoundForTheSameActor() {
        assertThatThrownBy(
                () -> authorization.authorize(generalAdmin, AdminPermission.PLATFORM_SECRET_MANAGE))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(
                () -> authorization.authorize(generalAdmin, AdminPermission.MENU_MANAGE, foreignProject))
                .isInstanceOf(ProjectNotVisibleException.class);
    }

    @Test
    void refusesTheWrongOverloadSoAProjectCheckIsNeverSkipped() {
        assertThatThrownBy(() -> authorization.authorize(generalAdmin, AdminPermission.BOARD_MANAGE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> authorization.authorize(
                superAdmin, AdminPermission.PLATFORM_SECRET_MANAGE, assignedProject))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reportsNavigationVisibilityWithoutRaising() {
        assertThat(authorization.permits(superAdmin, AdminPermission.PLATFORM_SECRET_MANAGE)).isTrue();
        assertThat(authorization.permits(generalAdmin, AdminPermission.PLATFORM_SECRET_MANAGE)).isFalse();
        assertThat(authorization.permits(generalAdmin, AdminPermission.MENU_MANAGE, assignedProject))
                .isTrue();
        assertThat(authorization.permits(generalAdmin, AdminPermission.MENU_MANAGE, foreignProject))
                .isFalse();
    }

    @Test
    void hidesProjectWorkspacesFromAnAdministratorWithoutAnyAssignment() {
        ActorContext unassigned =
                new ActorContext(UUID.randomUUID(), AdminRole.GENERAL_ADMIN, Set.of());

        assertThat(authorization.permits(unassigned, AdminPermission.MENU_MANAGE)).isFalse();
        assertThat(authorization.permits(generalAdmin, AdminPermission.MENU_MANAGE)).isTrue();
    }

    @Test
    void coversTheWholeMatrixWithExactlyTwoScopes() {
        assertThat(AdminPermission.values())
                .allMatch(permission -> permission.isPlatformGlobal() ^ permission.isProjectScoped());
    }
}

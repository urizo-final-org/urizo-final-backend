package org.urizo.axmodulestudio.backend.common.auth;

import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * The protected administrator and Project-membership path of {@code AXMS-FND-03}.
 *
 * <p>It exists so a bootstrapped {@code SUPER_ADMIN} can create the {@code GENERAL_ADMIN} the
 * acceptance criteria require. The complete member list, invitation workflow, and customer member
 * lifecycle remain {@code AXMS-CMS-01} work.
 */
public class AdminAccountService {

    /**
     * Placeholder minimum until a password policy is approved. It only rejects obviously unusable
     * values; complexity rules are not part of the MVP.
     */
    static final int MINIMUM_PASSWORD_LENGTH = 8;

    private static final int MAXIMUM_LOGIN_ID_LENGTH = 120;

    private final AuthOperations operations;
    private final AuthorizationService authorization;
    private final PasswordHasher hasher;
    private final Clock clock;

    public AdminAccountService(
            AuthOperations operations,
            AuthorizationService authorization,
            PasswordHasher hasher,
            Clock clock) {
        this.operations = operations;
        this.authorization = authorization;
        this.hasher = hasher;
        this.clock = clock;
    }

    /**
     * Creates an administrator account.
     *
     * <p>Granting {@code SUPER_ADMIN} goes through its own protected permission, keeping the
     * delivery-company role separate from ordinary account administration.
     *
     * @throws AccessDeniedException when the actor is not a {@code SUPER_ADMIN}
     * @throws AdminAccountConflictException when the login id is taken
     */
    public AdministratorSummary createAdministrator(
            ActorContext actor, String loginId, char[] password, AdminRole role) {
        Objects.requireNonNull(role, "role is required.");
        if (password == null) {
            throw new IllegalArgumentException("password is required.");
        }
        try {
            authorization.authorize(actor, role.isPlatformGlobal()
                    ? AdminPermission.SUPER_ADMIN_GRANT
                    : AdminPermission.ADMIN_ACCOUNT_MANAGE);
            String normalized = normalizeLoginId(loginId);
            requireUsablePassword(password);
            if (operations.existsByLoginId(normalized)) {
                throw new AdminAccountConflictException("The login id is already in use.");
            }
            AdminAccount account = new AdminAccount(
                    UUID.randomUUID(), normalized, hasher.hash(password), role,
                    AccountStatus.ACTIVE, now());
            operations.createAccount(account);
            return AdministratorSummary.of(account);
        }
        finally {
            Arrays.fill(password, '\0');
        }
    }

    /**
     * Disables an account and revokes its live sessions.
     *
     * <p>Without the revocation an already signed-in operator would keep working after the account
     * was closed.
     */
    public AdministratorSummary disableAdministrator(ActorContext actor, UUID accountId) {
        authorization.authorize(actor, AdminPermission.ADMIN_ACCOUNT_MANAGE);
        requireAccount(accountId);
        operations.updateAccountStatus(accountId, AccountStatus.DISABLED);
        operations.revokeAccountSessions(accountId, now());
        return AdministratorSummary.of(requireAccount(accountId));
    }

    /** Restores a disabled account. Existing sessions stay revoked and a fresh login is required. */
    public AdministratorSummary restoreAdministrator(ActorContext actor, UUID accountId) {
        authorization.authorize(actor, AdminPermission.ADMIN_ACCOUNT_MANAGE);
        requireAccount(accountId);
        operations.updateAccountStatus(accountId, AccountStatus.ACTIVE);
        return AdministratorSummary.of(requireAccount(accountId));
    }

    /**
     * Assigns a Project to a {@code GENERAL_ADMIN}.
     *
     * <p>A platform-global role already reaches every Project, so giving it a membership row would
     * record authority that does not exist.
     */
    public void assignProject(ActorContext actor, UUID accountId, UUID projectId) {
        authorization.authorize(actor, AdminPermission.PROJECT_MEMBERSHIP_ASSIGN);
        Objects.requireNonNull(projectId, "projectId is required.");
        AdminAccount account = requireAccount(accountId);
        if (account.role().isPlatformGlobal()) {
            throw new IllegalArgumentException(
                    "A platform-global administrator holds no Project membership.");
        }
        operations.addMembership(new ProjectMembership(accountId, projectId, now()));
    }

    /** Removes a Project assignment. Removing an absent assignment is not an error. */
    public void removeProject(ActorContext actor, UUID accountId, UUID projectId) {
        authorization.authorize(actor, AdminPermission.PROJECT_MEMBERSHIP_ASSIGN);
        Objects.requireNonNull(projectId, "projectId is required.");
        requireAccount(accountId);
        operations.removeMembership(accountId, projectId);
    }

    private AdminAccount requireAccount(UUID accountId) {
        Objects.requireNonNull(accountId, "accountId is required.");
        return operations.findAccountById(accountId)
                .orElseThrow(() -> new AdminAccountNotFoundException(
                        "The requested resource does not exist."));
    }

    private static String normalizeLoginId(String loginId) {
        if (loginId == null || loginId.isBlank()) {
            throw new IllegalArgumentException("loginId is required.");
        }
        String normalized = loginId.trim();
        if (normalized.length() > MAXIMUM_LOGIN_ID_LENGTH) {
            throw new IllegalArgumentException(
                    "loginId must not exceed " + MAXIMUM_LOGIN_ID_LENGTH + " characters.");
        }
        return normalized;
    }

    private static void requireUsablePassword(char[] password) {
        if (password.length < MINIMUM_PASSWORD_LENGTH) {
            throw new IllegalArgumentException(
                    "password must be at least " + MINIMUM_PASSWORD_LENGTH + " characters.");
        }
    }

    private Instant now() {
        return Instant.now(clock);
    }
}

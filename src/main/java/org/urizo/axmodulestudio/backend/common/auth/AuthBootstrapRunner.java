package org.urizo.axmodulestudio.backend.common.auth;

import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

/**
 * Creates the first administrator accounts so the deployment has someone who can sign in.
 *
 * <p>This is the one-time, non-public bootstrap path: the product has no administrator sign-up, and
 * the account-management API is not part of this Slice.
 *
 * <p>It writes through {@link AuthOperations} rather than {@link AdminAccountService} because that
 * service requires an authorizing actor, and at bootstrap time no account exists to be one.
 *
 * <p>Running it again is harmless. An existing login id is left untouched, so a restart never
 * resets a password an operator has come to rely on, and never revives an account somebody disabled
 * on purpose.
 */
final class AuthBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AuthBootstrapRunner.class);

    /** Matches the minimum the account service enforces, so both paths agree on what is usable. */
    private static final int MINIMUM_PASSWORD_LENGTH = AdminAccountService.MINIMUM_PASSWORD_LENGTH;

    private final AuthOperations operations;
    private final PasswordHasher hasher;
    private final Clock clock;
    private final AuthBootstrapProperties properties;

    AuthBootstrapRunner(
            AuthOperations operations,
            PasswordHasher hasher,
            Clock clock,
            AuthBootstrapProperties properties) {
        this.operations = operations;
        this.hasher = hasher;
        this.clock = clock;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.enabled()) {
            log.info("Administrator bootstrap is disabled; no account was created.");
            return;
        }
        seed(properties.superAdminLoginId(), properties.superAdminPassword(), AdminRole.SUPER_ADMIN);
        seed(properties.generalAdminLoginId(), properties.generalAdminPassword(),
                AdminRole.GENERAL_ADMIN);
    }

    private void seed(String loginId, String password, AdminRole role) {
        if (loginId == null || loginId.isBlank()) {
            log.warn("Administrator bootstrap skipped {}: no login id is configured.", role);
            return;
        }
        String normalized = loginId.trim();
        if (password == null || password.length() < MINIMUM_PASSWORD_LENGTH) {
            log.warn("Administrator bootstrap skipped {} '{}': the configured password is missing "
                    + "or shorter than {} characters.", role, normalized, MINIMUM_PASSWORD_LENGTH);
            return;
        }
        if (operations.existsByLoginId(normalized)) {
            log.info("Administrator bootstrap left {} '{}' unchanged; it already exists.",
                    role, normalized);
            return;
        }
        char[] value = password.toCharArray();
        try {
            operations.createAccount(new AdminAccount(
                    UUID.randomUUID(), normalized, hasher.hash(value), role,
                    AccountStatus.ACTIVE, Instant.now(clock)));
            log.info("Administrator bootstrap created {} '{}'.", role, normalized);
        }
        finally {
            Arrays.fill(value, '\0');
        }
    }
}

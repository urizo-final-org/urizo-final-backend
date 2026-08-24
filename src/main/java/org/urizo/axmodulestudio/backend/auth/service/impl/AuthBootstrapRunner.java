package org.urizo.axmodulestudio.backend.auth.service.impl;

import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.urizo.axmodulestudio.backend.auth.entity.AccountStatus;
import org.urizo.axmodulestudio.backend.auth.entity.AdminAccountEntity;
import org.urizo.axmodulestudio.backend.auth.entity.AdminRole;
import org.urizo.axmodulestudio.backend.auth.repository.AdminAccountRepository;
import org.urizo.axmodulestudio.backend.auth.config.AuthBootstrapProperties;

@Component
@Profile("local-full")
@Order(10)
public class AuthBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AuthBootstrapRunner.class);
    private static final int MINIMUM_PASSWORD_LENGTH = 8;

    private final AdminAccountRepository accounts;
    private final PasswordHasher passwordHasher;
    private final Clock clock;
    private final AuthBootstrapProperties properties;

    public AuthBootstrapRunner(
            AdminAccountRepository accounts,
            PasswordHasher passwordHasher,
            Clock clock,
            AuthBootstrapProperties properties) {
        this.accounts = accounts;
        this.passwordHasher = passwordHasher;
        this.clock = clock;
        this.properties = properties;
    }

    @Override
    @Transactional(transactionManager = "authJpaTransactionManager")
    public void run(ApplicationArguments args) {
        if (!properties.enabled()) {
            return;
        }
        seed(properties.superAdminLoginId(), properties.superAdminPassword(), "최고 관리자", AdminRole.SUPER_ADMIN);
        seed(properties.generalAdminLoginId(), properties.generalAdminPassword(),
                "일반 관리자", AdminRole.GENERAL_ADMIN);
        seed(properties.generalUserLoginId(), properties.generalUserPassword(),
                "일반 사용자", AdminRole.GENERAL_USER);
    }

    private void seed(String loginId, String password, String displayName, AdminRole role) {
        if (loginId == null || loginId.isBlank()
                || password == null || password.length() < MINIMUM_PASSWORD_LENGTH) {
            log.warn("Administrator bootstrap skipped {} because its configuration is incomplete.",
                    role);
            return;
        }
        String normalized = loginId.trim();
        if (accounts.existsByLoginId(normalized)) {
            return;
        }
        char[] value = password.toCharArray();
        try {
            accounts.save(new AdminAccountEntity(
                    UUID.randomUUID(), normalized, displayName, passwordHasher.hash(value), role,
                    AccountStatus.ACTIVE, Instant.now(clock)));
            log.info("Administrator bootstrap created {} account '{}'.", role, normalized);
        }
        finally {
            Arrays.fill(value, '\0');
        }
    }
}

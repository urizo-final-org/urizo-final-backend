package org.urizo.axmodulestudio.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ax.auth.bootstrap")
public record AuthBootstrapProperties(
        boolean enabled,
        String superAdminLoginId,
        String superAdminPassword,
        String generalAdminLoginId,
        String generalAdminPassword,
        String generalUserLoginId,
        String generalUserPassword) {
}

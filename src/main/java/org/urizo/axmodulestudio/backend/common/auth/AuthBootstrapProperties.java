package org.urizo.axmodulestudio.backend.common.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Credentials for the one-time administrator bootstrap.
 *
 * <p>There is no public administrator sign-up, so the first accounts have to arrive from
 * configuration. The values are supplied per environment; a blank password leaves that account
 * uncreated rather than creating one nobody can sign in to.
 *
 * @param enabled whether the bootstrap runs at all
 * @param superAdminLoginId login id of the delivery-company technical account
 * @param superAdminPassword its password, blank to skip creation
 * @param generalAdminLoginId login id of the customer-company operator account
 * @param generalAdminPassword its password, blank to skip creation
 */
@ConfigurationProperties("ax.auth.bootstrap")
public record AuthBootstrapProperties(
        boolean enabled,
        String superAdminLoginId,
        String superAdminPassword,
        String generalAdminLoginId,
        String generalAdminPassword) {
}

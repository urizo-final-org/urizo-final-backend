package org.urizo.axmodulestudio.backend.common.auth;

import java.time.Clock;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.urizo.axmodulestudio.backend.common.web.TraceIdFilter;

/**
 * Wiring for production authentication and authorization.
 *
 * <p>The auth beans are declared here instead of carrying their own stereotypes because they only
 * work where the Core DB adapter exists. A stereotype on each class would make the services
 * unconditional while their persistence port stayed profile-bound, and the default profile would
 * fail to start with no bean of type {@link AuthOperations}.
 *
 * <p>Keeping the profile decision in one place also means the later development-compatibility
 * separation changes this annotation only, not five classes.
 */
@Configuration(proxyBeanMethods = false)
@Profile("local-full")
@EnableConfigurationProperties({AuthProperties.class, AuthBootstrapProperties.class})
public class AuthConfiguration {

    /**
     * Puts production authentication in front of every protected route.
     *
     * <p>The registration is excluded under {@code dev-session}, where the development acceptance
     * path owns the same {@code /api/**} space. Both filters guard those routes and accept different
     * tokens, so running them together would reject every request no matter which token it carried.
     *
     * <p>The order places it directly behind {@link TraceIdFilter}, whose request attribute the
     * unauthenticated response body needs.
     */
    @Bean
    @Profile("!dev-session")
    FilterRegistrationBean<ProductionAuthFilter> productionAuthFilterRegistration(
            AuthenticationService authentication, ObjectMapper objectMapper) {
        FilterRegistrationBean<ProductionAuthFilter> registration = new FilterRegistrationBean<>(
                new ProductionAuthFilter(authentication, objectMapper));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }

    /**
     * Enforces the platform-global rows of the permission matrix.
     *
     * <p>It runs directly behind authentication so the actor it reads is already resolved, and it
     * shares that filter's profile condition: without production authentication there is no actor to
     * authorize.
     */
    @Bean
    @Profile("!dev-session")
    FilterRegistrationBean<AdminAuthorizationFilter> adminAuthorizationFilterRegistration(
            AuthorizationService authorization, ObjectMapper objectMapper) {
        FilterRegistrationBean<AdminAuthorizationFilter> registration = new FilterRegistrationBean<>(
                new AdminAuthorizationFilter(authorization, objectMapper));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        return registration;
    }

    @Bean
    AuthOperations authOperations(
            @Qualifier("productJdbcTemplate") JdbcTemplate productJdbcTemplate,
            @Qualifier("productTransactionTemplate") TransactionTemplate productTransactionTemplate) {
        return new JdbcAuthStore(productJdbcTemplate, productTransactionTemplate);
    }

    @Bean
    PasswordHasher passwordHasher() {
        return new PasswordHasher();
    }

    @Bean
    AuthorizationService authorizationService() {
        return new AuthorizationService();
    }

    @Bean
    AuthenticationService authenticationService(
            AuthOperations operations,
            PasswordHasher hasher,
            Clock clock,
            AuthProperties properties) {
        return new AuthenticationService(operations, hasher, clock, properties);
    }

    @Bean
    AuthBootstrapRunner authBootstrapRunner(
            AuthOperations operations,
            PasswordHasher hasher,
            Clock clock,
            AuthBootstrapProperties properties) {
        return new AuthBootstrapRunner(operations, hasher, clock, properties);
    }

    @Bean
    AdminAccountService adminAccountService(
            AuthOperations operations,
            AuthorizationService authorization,
            PasswordHasher hasher,
            Clock clock) {
        return new AdminAccountService(operations, authorization, hasher, clock);
    }
}

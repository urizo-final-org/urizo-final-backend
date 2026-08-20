package org.urizo.axmodulestudio.backend.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.urizo.axmodulestudio.backend.auth.service.impl.PasswordHasher;

@Configuration(proxyBeanMethods = false)
@Profile("local-full")
@EnableConfigurationProperties({JwtProperties.class, AuthBootstrapProperties.class})
public class AuthConfig {

    @Bean
    PasswordHasher passwordHasher() {
        return new PasswordHasher();
    }
}

package org.urizo.axmodulestudio.backend.integration.ai.observability;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("local-full")
@EnableConfigurationProperties(LangfuseProperties.class)
public class LangfuseConfiguration {

    @Bean
    LangfuseHttpTransport langfuseHttpTransport(LangfuseProperties properties) {
        return new JdkLangfuseHttpTransport(properties.connectTimeout());
    }
}

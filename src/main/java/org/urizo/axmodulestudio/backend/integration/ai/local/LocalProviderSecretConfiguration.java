package org.urizo.axmodulestudio.backend.integration.ai.local;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
@Profile("dev")
@EnableConfigurationProperties(LocalProviderSecretProperties.class)
public class LocalProviderSecretConfiguration {

    @Bean
    DataSource localProviderSecretDataSource(LocalProviderSecretProperties properties) throws IOException {
        String password = Files.readString(properties.databasePasswordFile(), StandardCharsets.UTF_8).trim();
        if (password.isEmpty()) {
            throw new IllegalStateException("Local CMS database credential file is empty.");
        }

        HikariConfig config = new HikariConfig();
        config.setPoolName("axms-local-provider-secret-pool");
        config.setJdbcUrl(properties.jdbcUrl());
        config.setUsername("cms_app");
        config.setPassword(password);
        config.setMaximumPoolSize(3);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(5_000);
        config.setValidationTimeout(2_000);
        config.setAutoCommit(true);
        return new HikariDataSource(config);
    }

    @Bean
    JdbcTemplate localProviderSecretJdbcTemplate(
            @Qualifier("localProviderSecretDataSource") DataSource localProviderSecretDataSource) {
        return new JdbcTemplate(localProviderSecretDataSource);
    }

    @Bean
    ProviderSecretCrypto providerSecretCrypto(LocalProviderSecretProperties properties) throws IOException {
        byte[] masterKey = Files.readAllBytes(properties.masterKeyFile());
        try {
            return new ProviderSecretCrypto(masterKey);
        }
        finally {
            java.util.Arrays.fill(masterKey, (byte) 0);
        }
    }
}

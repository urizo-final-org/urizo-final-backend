package org.urizo.axmodulestudio.backend.coding.config;

import org.urizo.axmodulestudio.backend.coding.repository.CodingModelTurnGuard;
import org.urizo.axmodulestudio.backend.coding.repository.JdbcCodingModelTurnGuard;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;

import javax.sql.DataSource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@Profile("dev")
@ConditionalOnProperty(prefix = "ax.coding.model-turn-bridge", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(CodingModelTurnDatabaseProperties.class)
public class CodingModelTurnDatabaseConfiguration {

    @Bean
    DataSource codingModelTurnDataSource(CodingModelTurnDatabaseProperties properties) throws IOException {
        String password = Files.readString(properties.passwordFile(), StandardCharsets.UTF_8).trim();
        if (password.isEmpty()) {
            throw new IllegalStateException("Coding Model Turn database credential file is empty.");
        }
        HikariConfig config = new HikariConfig();
        config.setPoolName("axms-coding-model-turn-pool");
        config.setJdbcUrl(properties.jdbcUrl());
        config.setUsername("ai_workspace");
        config.setPassword(password);
        config.setMaximumPoolSize(4);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(5_000);
        config.setValidationTimeout(2_000);
        return new HikariDataSource(config);
    }

    @Bean
    JdbcTemplate codingModelTurnJdbcTemplate(
            @Qualifier("codingModelTurnDataSource") DataSource codingModelTurnDataSource) {
        return new JdbcTemplate(codingModelTurnDataSource);
    }

    @Bean
    TransactionTemplate codingModelTurnTransactionTemplate(
            @Qualifier("codingModelTurnDataSource") DataSource codingModelTurnDataSource) {
        return new TransactionTemplate(new DataSourceTransactionManager(codingModelTurnDataSource));
    }

    @Bean
    CodingModelTurnGuard codingModelTurnGuard(
            @Qualifier("codingModelTurnJdbcTemplate") JdbcTemplate codingModelTurnJdbcTemplate,
            @Qualifier("codingModelTurnTransactionTemplate")
            TransactionTemplate codingModelTurnTransactionTemplate,
            ObjectMapper objectMapper,
            Clock clock,
            CodingModelTurnDatabaseProperties properties) {
        return new JdbcCodingModelTurnGuard(
                codingModelTurnJdbcTemplate,
                codingModelTurnTransactionTemplate,
                objectMapper,
                clock,
                properties.leaseDuration());
    }
}

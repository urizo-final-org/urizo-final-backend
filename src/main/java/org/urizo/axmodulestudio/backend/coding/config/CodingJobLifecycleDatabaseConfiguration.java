package org.urizo.axmodulestudio.backend.coding.config;

import org.urizo.axmodulestudio.backend.coding.repository.CodingJobLifecycleRepository;
import org.urizo.axmodulestudio.backend.coding.repository.JdbcCodingJobLifecycleRepository;
import org.urizo.axmodulestudio.backend.coding.service.CodingJobLifecycleRequestDigester;
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
@Profile("dev & coding-job-local-fixture")
@ConditionalOnProperty(prefix = "ax.coding.job-lifecycle", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(CodingJobLifecycleDatabaseProperties.class)
public class CodingJobLifecycleDatabaseConfiguration {

    @Bean
    DataSource codingJobLifecycleDataSource(CodingJobLifecycleDatabaseProperties properties)
            throws IOException {
        String password = Files.readString(properties.passwordFile(), StandardCharsets.UTF_8).trim();
        if (password.isEmpty()) {
            throw new IllegalStateException("Coding job lifecycle database credential file is empty.");
        }
        HikariConfig config = new HikariConfig();
        config.setPoolName("axms-coding-job-lifecycle-pool");
        config.setJdbcUrl(properties.jdbcUrl());
        config.setUsername("dev_operator");
        config.setPassword(password);
        config.setMaximumPoolSize(2);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(5_000);
        config.setValidationTimeout(2_000);
        return new HikariDataSource(config);
    }

    @Bean
    JdbcTemplate codingJobLifecycleJdbcTemplate(
            @Qualifier("codingJobLifecycleDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    TransactionTemplate codingJobLifecycleTransactionTemplate(
            @Qualifier("codingJobLifecycleDataSource") DataSource dataSource) {
        return new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Bean
    CodingJobLifecycleRequestDigester codingJobLifecycleRequestDigester(ObjectMapper objectMapper) {
        return new CodingJobLifecycleRequestDigester(objectMapper);
    }

    @Bean
    CodingJobLifecycleRepository codingJobLifecycleRepository(
            @Qualifier("codingJobLifecycleJdbcTemplate") JdbcTemplate jdbcTemplate,
            @Qualifier("codingJobLifecycleTransactionTemplate") TransactionTemplate transactionTemplate,
            ObjectMapper objectMapper,
            Clock clock) {
        return new JdbcCodingJobLifecycleRepository(
                jdbcTemplate, transactionTemplate, objectMapper, clock);
    }
}

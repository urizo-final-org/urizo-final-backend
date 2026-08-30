package org.urizo.axmodulestudio.backend.knowledge.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@Profile("local-full")
@EnableScheduling
@EnableConfigurationProperties({ProductRuntimeProperties.class, EmbeddingProperties.class})
public class ProductRuntimeConfiguration {

    @Bean(destroyMethod = "close")
    @Primary
    DataSource productDataSource(ProductRuntimeProperties properties) throws IOException {
        String password = readSecret(properties.databasePasswordFile(), "product database credential");
        HikariConfig config = new HikariConfig();
        config.setPoolName("axms-product-pool");
        config.setJdbcUrl(properties.jdbcUrl());
        config.setUsername("cms_app");
        config.setPassword(password);
        config.setMaximumPoolSize(8);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(5_000);
        config.setValidationTimeout(2_000);
        config.setAutoCommit(false);
        return new HikariDataSource(config);
    }

    @Bean
    @Primary
    JdbcTemplate productJdbcTemplate(
            @Qualifier("productDataSource") DataSource productDataSource) {
        return new JdbcTemplate(productDataSource);
    }

    @Bean
    @Primary
    PlatformTransactionManager productTransactionManager(
            @Qualifier("productDataSource") DataSource productDataSource) {
        return new DataSourceTransactionManager(productDataSource);
    }

    @Bean
    TransactionTemplate productTransactionTemplate(
            @Qualifier("productTransactionManager") PlatformTransactionManager manager) {
        return new TransactionTemplate(manager);
    }

    @Bean(destroyMethod = "destroy")
    LettuceConnectionFactory productRedisConnectionFactory(ProductRuntimeProperties properties)
            throws IOException {
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(
                properties.valkeyHost(), properties.valkeyPort());
        configuration.setPassword(RedisPassword.of(
                readSecret(properties.valkeyPasswordFile(), "Valkey credential")));
        LettuceConnectionFactory factory = new LettuceConnectionFactory(configuration);
        factory.setValidateConnection(true);
        return factory;
    }

    @Bean
    StringRedisTemplate productRedisTemplate(
            LettuceConnectionFactory productRedisConnectionFactory) {
        return new StringRedisTemplate(productRedisConnectionFactory);
    }

    private static String readSecret(Path path, String label) throws IOException {
        String value = Files.readString(path, StandardCharsets.UTF_8).trim();
        if (value.isEmpty()) {
            throw new IllegalStateException(label + " file is empty.");
        }
        return value;
    }
}

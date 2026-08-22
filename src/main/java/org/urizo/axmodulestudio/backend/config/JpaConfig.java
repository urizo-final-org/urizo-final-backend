package org.urizo.axmodulestudio.backend.config;

import java.util.Map;

import javax.sql.DataSource;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.urizo.axmodulestudio.backend.auth.entity.AdminAccountEntity;
import org.urizo.axmodulestudio.backend.auth.repository.AdminAccountRepository;
import org.urizo.axmodulestudio.backend.cms.entity.CmsMenuEntity;
import org.urizo.axmodulestudio.backend.cms.repository.CmsMenuJpaRepository;

/** JPA uses the product data source while Flyway remains the only Core DDL owner. */
@Configuration(proxyBeanMethods = false)
@Profile("local-full")
@EnableJpaRepositories(
        basePackageClasses = {AdminAccountRepository.class, CmsMenuJpaRepository.class},
        entityManagerFactoryRef = "authEntityManagerFactory",
        transactionManagerRef = "authJpaTransactionManager")
public class JpaConfig {

    @Bean(name = "authEntityManagerFactory")
    LocalContainerEntityManagerFactoryBean authEntityManagerFactory(
            @Qualifier("productDataSource") DataSource productDataSource) {
        HibernateJpaVendorAdapter vendorAdapter = new HibernateJpaVendorAdapter();
        vendorAdapter.setGenerateDdl(false);
        vendorAdapter.setShowSql(false);

        LocalContainerEntityManagerFactoryBean factory =
                new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(productDataSource);
        factory.setJpaVendorAdapter(vendorAdapter);
        factory.setPackagesToScan(
                AdminAccountEntity.class.getPackageName(),
                CmsMenuEntity.class.getPackageName());
        factory.setPersistenceUnitName("axms-business");
        factory.setJpaPropertyMap(Map.of(
                "hibernate.hbm2ddl.auto", "validate",
                "hibernate.default_schema", "app",
                "hibernate.jdbc.time_zone", "UTC"));
        return factory;
    }

    @Bean(name = "authJpaTransactionManager")
    PlatformTransactionManager authJpaTransactionManager(
            @Qualifier("authEntityManagerFactory") EntityManagerFactory entityManagerFactory) {
        return new JpaTransactionManager(entityManagerFactory);
    }
}

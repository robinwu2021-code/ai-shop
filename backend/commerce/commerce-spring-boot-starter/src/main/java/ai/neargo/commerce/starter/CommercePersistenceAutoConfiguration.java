package ai.neargo.commerce.starter;

import ai.neargo.commerce.api.port.CommerceEventPersistencePort;
import ai.neargo.commerce.api.port.CommerceIdGenerator;
import ai.neargo.commerce.core.catalog.CatalogRepository;
import ai.neargo.commerce.core.spi.*;
import ai.neargo.commerce.persistence.*;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.flyway.autoconfigure.*;
import org.springframework.context.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/** Opt-in only. Missing host Flyway initializer or shared JDBC manager is an assembly failure. */
@AutoConfiguration(after = FlywayAutoConfiguration.class)
@ConditionalOnProperty(prefix = "commerce", name = "enabled", havingValue = "true")
public class CommercePersistenceAutoConfiguration {
    @Bean
    CommerceMigrationInitializer commerceMigrationInitializer(
            DataSource ds, FlywayMigrationInitializer hostInitializer) {
        return new CommerceMigrationInitializer(ds, hostInitializer);
    }

    @Bean
    @DependsOn("commerceMigrationInitializer")
    @ConditionalOnMissingBean(CommerceUnitOfWork.class)
    CommerceUnitOfWork commerceUnitOfWork(DataSource ds, PlatformTransactionManager manager) {
        return new JdbcCommerceUnitOfWork(ds, manager);
    }

    @Bean
    @DependsOn("commerceMigrationInitializer")
    @ConditionalOnMissingBean(CommerceEventPersistencePort.class)
    CommerceEventPersistencePort commerceEventPersistence(DataSource ds) {
        return new JdbcCommerceEventPersistence(ds);
    }

    @Bean
    @DependsOn("commerceMigrationInitializer")
    @ConditionalOnProperty(prefix = "commerce.catalog", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(CatalogRepository.class)
    CatalogRepository commerceCatalogRepository(DataSource ds) {
        return new JdbcCatalogRepository(ds);
    }

    @Bean
    @DependsOn("commerceMigrationInitializer")
    @ConditionalOnMissingBean(SubscriptionAccountRepository.class)
    SubscriptionAccountRepository commerceSubscriptionAccounts(DataSource ds) {
        return new JdbcSubscriptionAccountRepository(ds);
    }

    /**
     * Opaque, collision-free business numbers (32 hex chars). Every commerce identifier is
     * persisted with unique keys, so randomness is the only property required. A host may
     * provide its own generator (for example with readable prefixes) and this one steps aside.
     */
    @Bean
    @ConditionalOnMissingBean(CommerceIdGenerator.class)
    CommerceIdGenerator commerceIdGenerator() {
        return namespace -> java.util.UUID.randomUUID().toString().replace("-", "");
    }
}

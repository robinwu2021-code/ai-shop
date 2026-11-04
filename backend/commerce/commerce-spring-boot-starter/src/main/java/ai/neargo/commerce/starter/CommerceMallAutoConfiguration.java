package ai.neargo.commerce.starter;

import ai.neargo.commerce.api.mall.MallFacade;
import ai.neargo.commerce.api.port.*;
import ai.neargo.commerce.core.catalog.CatalogRepository;
import ai.neargo.commerce.core.mall.*;
import ai.neargo.commerce.core.spi.*;
import ai.neargo.commerce.persistence.*;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.*;

import java.time.Clock;

import javax.sql.DataSource;

/**
 * T06 explicit service opt-in. Trusted host identity/store ports are mandatory. Host purchase
 * availability must remain closed until payment/inventory/delivery ports are ready.
 */
@AutoConfiguration(
        after = {
            CommercePersistenceAutoConfiguration.class,
            CommerceCatalogAutoConfiguration.class
        })
@ConditionalOnProperty(
        prefix = "commerce",
        name = {"enabled", "mall.enabled"},
        havingValue = "true")
public class CommerceMallAutoConfiguration {
    @Bean
    @DependsOn("commerceMigrationInitializer")
    @ConditionalOnMissingBean(MallRepository.class)
    MallRepository commerceMallRepository(DataSource ds) {
        return new JdbcMallRepository(ds);
    }

    @Bean
    @DependsOn("commerceMigrationInitializer")
    @ConditionalOnMissingBean(CatalogRepository.class)
    CatalogRepository commerceMallCatalogRepository(DataSource ds) {
        return new JdbcCatalogRepository(ds);
    }

    @Bean
    @ConditionalOnMissingBean(MallFacade.class)
    MallFacade commerceMallFacade(
            MallRepository orders,
            CatalogRepository catalog,
            SubscriptionAccountRepository accounts,
            CommerceUnitOfWork uow,
            StoreDirectoryPort stores,
            ActorAuthorizationPort authorization,
            CommerceIdGenerator ids) {
        return new DefaultMallFacade(
                orders, catalog, accounts, uow, stores, authorization, Clock.systemUTC(), ids);
    }
}

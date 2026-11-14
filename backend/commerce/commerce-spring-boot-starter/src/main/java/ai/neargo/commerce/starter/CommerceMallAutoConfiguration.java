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
    MallRepository commerceMallRepository(DataSource ds, CommerceEventPersistencePort events) {
        return new JdbcMallRepository(ds, events);
    }

    @Bean
    @DependsOn("commerceMigrationInitializer")
    @ConditionalOnMissingBean(CatalogRepository.class)
    CatalogRepository commerceMallCatalogRepository(DataSource ds) {
        return new JdbcCatalogRepository(ds);
    }

    @Bean
    @ConditionalOnMissingBean(ai.neargo.commerce.api.mall.StorefrontFacade.class)
    ai.neargo.commerce.api.mall.StorefrontFacade commerceStorefrontFacade(CatalogRepository catalog,
            MallRepository mall, SubscriptionAccountRepository accounts, CommerceUnitOfWork uow,
            StoreDirectoryPort stores, ActorAuthorizationPort auth, CheckoutReadinessPort readiness,
            org.springframework.beans.factory.ObjectProvider<ai.neargo.commerce.api.port.OfflineCollectionPort> offline,
            org.springframework.core.env.Environment env) {
        // Same opt-in as CommerceOfflineAutoConfiguration: offline readiness only when the offline
        // workflow is enabled with a collection profile and a trusted host collection port.
        boolean enabled = env.getProperty("commerce.mall.payment.enabled", Boolean.class, false)
                && env.getProperty("commerce.mall.offline.enabled", Boolean.class, false);
        String profileNo = env.getProperty("commerce.mall.offline.profile-no", "").trim();
        var collection = enabled && !profileNo.isEmpty() ? offline.getIfAvailable() : null;
        if (collection == null)
            return new DefaultStorefrontFacade(catalog, mall, accounts, uow, stores, auth, readiness);
        return new DefaultStorefrontFacade(
                catalog, mall, accounts, uow, stores, auth, readiness, collection, profileNo);
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
            CheckoutReadinessPort readiness,
            CommerceIdGenerator ids,
            org.springframework.beans.factory.ObjectProvider<
                            ai.neargo.commerce.api.mall.RefundFacade>
                    refunds, org.springframework.beans.factory.ObjectProvider<MoneyPolicyPort> policies) {
        return new DefaultMallFacade(
                orders,
                catalog,
                accounts,
                uow,
                stores,
                authorization,
                Clock.systemUTC(),
                ids,
                refunds.getIfAvailable(), readiness, policies.getIfAvailable(() -> MoneyPolicyPort.CNY_COMPATIBILITY));
    }
}

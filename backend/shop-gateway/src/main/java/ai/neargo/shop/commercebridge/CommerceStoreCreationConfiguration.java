package ai.neargo.shop.commercebridge;

import ai.neargo.shop.auth.PermChecker;
import ai.neargo.shop.common.config.ModuleDataSources;
import org.springframework.beans.factory.annotation.Qualifier;

import ai.neargo.commerce.api.port.*;
import ai.neargo.commerce.api.subscription.StoreProvisioningFacade;
import ai.neargo.commerce.core.access.EndScheduleService;
import ai.neargo.commerce.core.catalog.CatalogRepository;
import ai.neargo.commerce.core.spi.SubscriptionRepository;
import org.springframework.beans.factory.ObjectProvider;
import ai.neargo.commerce.core.spi.CommerceUnitOfWork;
import ai.neargo.commerce.core.subscription.*;
import ai.neargo.commerce.persistence.*;
import ai.neargo.shop.spi.pay.MarketPort;
import ai.neargo.shop.spi.pay.MarketPort.MarketBrief;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

import java.time.Clock;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.sql.DataSource;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "commerce",
        name = {"enabled", "store-creation.enabled"},
        havingValue = "true")
class CommerceStoreCreationConfiguration {
    @Bean
    @DependsOn("commerceMigrationInitializer")
    HostStoreCreationAuthority hostStoreCreationAuthority(@Qualifier(ModuleDataSources.PLATFORM_DATA_SOURCE) DataSource ds, MarketPort markets, PermChecker permissions) {
        // Market currency is immutable in the host contract. Resolve it before any store
        // transaction.
        var currencies =
                markets.all().stream()
                        .collect(Collectors.toMap(MarketBrief::market, MarketBrief::currency));
        return new HostStoreCreationAuthority(ds, currencies, permissions);
    }

    @Bean
    StoreProvisioningFacade storeProvisioningFacade(
            DataSource ds,
            CommerceUnitOfWork uow,
            CommerceEventPersistencePort events,
            HostStoreCreationAuthority authority,
            ObjectProvider<CatalogRepository> catalogs, ObjectProvider<SubscriptionRepository> subscriptions) {
        CommerceIdGenerator ids = namespace -> UUID.randomUUID().toString();
        var clock = Clock.systemUTC();
        var repo = subscriptions.getIfAvailable(() -> new JdbcSubscriptionRepository(ds, ids));
        var schedule = new EndScheduleService(repo, uow, events, ids, clock);
        var subscription =
                new SubscriptionService(
                        repo, catalogs.getIfAvailable(() -> new JdbcCatalogRepository(ds)), uow, schedule, events, ids, clock);
        return new StoreProvisioningService(subscription, authority);
    }
}

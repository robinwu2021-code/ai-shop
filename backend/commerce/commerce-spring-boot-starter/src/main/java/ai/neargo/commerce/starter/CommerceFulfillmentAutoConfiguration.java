package ai.neargo.commerce.starter;

import ai.neargo.commerce.api.port.*;
import ai.neargo.commerce.core.access.EndScheduleService;
import ai.neargo.commerce.core.catalog.CatalogRepository;
import ai.neargo.commerce.core.fulfillment.*;
import ai.neargo.commerce.core.mall.MallRepository;
import ai.neargo.commerce.core.spi.*;
import ai.neargo.commerce.core.subscription.SubscriptionService;
import ai.neargo.commerce.persistence.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.*;

import java.time.Clock;
import java.util.*;

import javax.sql.DataSource;

/** Separate opt-in: missing configured platform collection or trusted bridges fails startup. */
@AutoConfiguration(after = CommerceMallAutoConfiguration.class)
@ConditionalOnProperty(
        prefix = "commerce",
        name = {"enabled", "mall.enabled", "mall.payment.enabled"},
        havingValue = "true")
public class CommerceFulfillmentAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(FulfillmentRepository.class)
    @DependsOn("commerceMigrationInitializer")
    FulfillmentRepository commerceFulfillmentRepository(DataSource ds) {
        return new JdbcFulfillmentRepository(ds);
    }

    @Bean
    @ConditionalOnMissingBean(SubscriptionRepository.class)
    @DependsOn("commerceMigrationInitializer")
    SubscriptionRepository commerceSubscriptionRepository(DataSource ds, CommerceIdGenerator ids) {
        return new JdbcSubscriptionRepository(ds, ids);
    }

    @Bean
    @ConditionalOnMissingBean(EndScheduleService.class)
    EndScheduleService commerceEndScheduleService(
            SubscriptionRepository repo,
            CommerceUnitOfWork uow,
            CommerceEventPersistencePort events,
            CommerceIdGenerator ids) {
        return new EndScheduleService(repo, uow, events, ids, Clock.systemUTC());
    }

    @Bean
    @ConditionalOnMissingBean(SubscriptionService.class)
    SubscriptionService commerceSubscriptionService(
            SubscriptionRepository repo,
            CatalogRepository catalog,
            CommerceUnitOfWork uow,
            EndScheduleService schedule,
            CommerceEventPersistencePort events,
            CommerceIdGenerator ids) {
        return new SubscriptionService(
                repo, catalog, uow, schedule, events, ids, Clock.systemUTC());
    }

    @Bean
    @ConditionalOnMissingBean(CommerceFulfillmentService.class)
    CommerceFulfillmentService commerceFulfillmentService(
            MallRepository mall,
            FulfillmentRepository repo,
            SubscriptionAccountRepository accounts,
            CommerceUnitOfWork uow,
            SubscriptionService subscriptions,
            PlatformPaymentPort payment,
            HardwareInventoryPort inventory,
            CommerceEventPersistencePort events,
            CommerceIdGenerator ids,
            ActorAuthorizationPort authorization,
            PaidStoreActivationPort activation,
            @Value("${commerce.mall.payment.platform-merchant-no}") String recipient,
            @Value("${commerce.mall.payment.routes}") String routes) {
        uow.requireOutsideTransaction();
        var allowed = new HashSet<String>();
        for (String route : routes.split(",")) {
            route = route.trim();
            if (!route.matches("[A-Z0-9_]+:[A-Z0-9_]+"))
                throw new IllegalArgumentException("explicit CHANNEL:METHOD routes required");
            allowed.add(route);
        }
        return new CommerceFulfillmentService(
                mall,
                repo,
                accounts,
                uow,
                subscriptions,
                payment,
                inventory,
                events,
                Clock.systemUTC(),
                ids,
                recipient,
                allowed,
                authorization, activation);
    }
}

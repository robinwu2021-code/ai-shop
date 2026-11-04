package ai.neargo.commerce.starter;

import ai.neargo.commerce.api.port.*;
import ai.neargo.commerce.api.subscription.SourceManagementFacade;
import ai.neargo.commerce.core.access.EndScheduleService;
import ai.neargo.commerce.core.catalog.CatalogRepository;
import ai.neargo.commerce.core.spi.*;
import ai.neargo.commerce.core.subscription.DefaultSourceManagementFacade;
import ai.neargo.commerce.persistence.JdbcSubscriptionRepository;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.*;

import java.time.Clock;

import javax.sql.DataSource;

@AutoConfiguration(
        after = CommerceCatalogAutoConfiguration.class,
        before = CommerceFulfillmentAutoConfiguration.class)
@ConditionalOnProperty(
        prefix = "commerce",
        name = {"enabled", "catalog.enabled", "mall.http.enabled"},
        havingValue = "true")
public class CommerceSourceManagementAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(SubscriptionRepository.class)
    @DependsOn("commerceMigrationInitializer")
    SubscriptionRepository commerceSourceRepository(DataSource ds, CommerceIdGenerator ids) {
        return new JdbcSubscriptionRepository(ds, ids);
    }

    @Bean
    @ConditionalOnMissingBean(EndScheduleService.class)
    EndScheduleService commerceSourceSchedule(
            SubscriptionRepository repository,
            CommerceUnitOfWork uow,
            CommerceEventPersistencePort events,
            CommerceIdGenerator ids) {
        return new EndScheduleService(repository, uow, events, ids, Clock.systemUTC());
    }

    @Bean
    @ConditionalOnMissingBean(SourceManagementFacade.class)
    SourceManagementFacade commerceSourceManagement(
            SubscriptionRepository repository,
            CatalogRepository catalog,
            CommerceUnitOfWork uow,
            StoreDirectoryPort stores,
            ActorAuthorizationPort auth,
            BusinessEndReadinessPort readiness,
            EndScheduleService schedule,
            CommerceEventPersistencePort events,
            CommerceIdGenerator ids) {
        return new DefaultSourceManagementFacade(
                repository,
                catalog,
                uow,
                stores,
                auth,
                readiness,
                schedule,
                events,
                ids,
                Clock.systemUTC());
    }
}

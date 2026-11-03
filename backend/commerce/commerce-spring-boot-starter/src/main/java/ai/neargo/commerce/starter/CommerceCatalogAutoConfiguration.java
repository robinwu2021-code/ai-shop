package ai.neargo.commerce.starter;

import ai.neargo.commerce.api.catalog.CatalogFacade;
import ai.neargo.commerce.api.port.*;
import ai.neargo.commerce.core.catalog.*;
import ai.neargo.commerce.core.spi.CommerceUnitOfWork;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.Bean;

import java.time.Clock;

/**
 * A second explicit opt-in: trusted platform authorization and deployment evidence are mandatory.
 */
@AutoConfiguration(after = CommercePersistenceAutoConfiguration.class)
@ConditionalOnProperty(
        prefix = "commerce",
        name = {"enabled", "catalog.enabled"},
        havingValue = "true")
public class CommerceCatalogAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(CatalogFacade.class)
    CatalogFacade commerceCatalogFacade(
            CatalogRepository repository,
            CommerceUnitOfWork uow,
            CatalogAuthorizationPort authorization,
            BusinessEndReadinessPort readiness,
            CommerceEventPersistencePort events,
            CommerceIdGenerator ids) {
        return new DefaultCatalogFacade(
                repository, uow, authorization, readiness, events, Clock.systemUTC(), ids);
    }
}

package ai.neargo.commerce.starter;

import ai.neargo.commerce.api.foundation.*;
import ai.neargo.commerce.core.foundation.FoundationQueryService;
import ai.neargo.commerce.core.spi.CommerceUnitOfWork;
import ai.neargo.commerce.persistence.*;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.*;

import java.time.Clock;
import java.util.UUID;

import javax.sql.DataSource;

/** Import for merchant reads independently of operator catalog write ports. */
@Configuration(proxyBeanMethods = false)
public class CommerceFoundationConfiguration {
    @Bean
    FoundationQueryFacade commerceFoundationQueries(
            DataSource ds,
            CommerceUnitOfWork uow,
            FoundationAuthorityPort host,
            ObjectProvider<Clock> clocks) {
        return new FoundationQueryService(
                new JdbcCatalogRepository(ds),
                new JdbcSubscriptionRepository(ds, ns -> UUID.randomUUID().toString()),
                uow,
                host,
                clocks.getIfAvailable(Clock::systemUTC));
    }
}

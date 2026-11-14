package ai.neargo.commerce.starter;

import ai.neargo.commerce.api.mall.MallQueryFacade;
import ai.neargo.commerce.api.port.*;
import ai.neargo.commerce.core.fulfillment.FulfillmentRepository;
import ai.neargo.commerce.core.mall.*;
import ai.neargo.commerce.core.query.DefaultMallQueryFacade;
import ai.neargo.commerce.core.spi.*;
import ai.neargo.commerce.persistence.*;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.*;

import javax.sql.DataSource;

/** Read models do not activate payment/refund execution or require SDK credentials. */
@AutoConfiguration(
        after = CommerceMallAutoConfiguration.class,
        before = {
            CommerceFulfillmentAutoConfiguration.class,
            CommerceRefundAutoConfiguration.class
        })
@ConditionalOnProperty(
        prefix = "commerce",
        name = {"enabled", "mall.enabled"},
        havingValue = "true")
public class CommerceMallQueryAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(FulfillmentRepository.class)
    @DependsOn("commerceMigrationInitializer")
    FulfillmentRepository commerceQueryOperations(DataSource ds) {
        return new JdbcFulfillmentRepository(ds);
    }

    @Bean
    @ConditionalOnMissingBean(RefundRepository.class)
    @DependsOn("commerceMigrationInitializer")
    RefundRepository commerceQueryRefunds(DataSource ds, CommerceIdGenerator ids) {
        return new JdbcRefundRepository(ds, ids);
    }

    @Bean
    @ConditionalOnMissingBean(MallQueryFacade.class)
    MallQueryFacade commerceMallQueries(
            MallRepository mall,
            FulfillmentRepository operations,
            RefundRepository refunds,
            SubscriptionAccountRepository accounts,
            CommerceUnitOfWork uow,
            StoreDirectoryPort stores,
            ActorAuthorizationPort auth,
            org.springframework.beans.factory.ObjectProvider<
                            ai.neargo.commerce.core.offline.OfflineRepository>
                    offline) {
        return new DefaultMallQueryFacade(
                mall, operations, refunds, accounts, uow, stores, auth, offline.getIfAvailable());
    }
}

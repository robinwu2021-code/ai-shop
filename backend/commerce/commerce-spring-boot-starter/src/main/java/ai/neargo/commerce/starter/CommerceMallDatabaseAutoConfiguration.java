package ai.neargo.commerce.starter;

import ai.neargo.commerce.api.mall.reporting.PurchaseOrderQueryPort;
import ai.neargo.commerce.api.port.*;
import ai.neargo.commerce.core.catalog.CatalogRepository;
import ai.neargo.commerce.core.fulfillment.FulfillmentRepository;
import ai.neargo.commerce.core.mall.MallRepository;
import ai.neargo.commerce.core.mall.RefundRepository;
import ai.neargo.commerce.core.offline.OfflineRepository;
import ai.neargo.commerce.core.spi.*;
import ai.neargo.commerce.persistence.*;
import javax.sql.DataSource;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;
import org.springframework.transaction.PlatformTransactionManager;

/** Bind the repositories and the transaction that guards their writes to the same mall pool. */
@AutoConfiguration(before = CommercePersistenceAutoConfiguration.class)
@ConditionalOnProperty(prefix = "commerce", name = "enabled", havingValue = "true")
@ConditionalOnProperty(name = "shop.modules.mall.datasource")
public class CommerceMallDatabaseAutoConfiguration {
    record MallStorage(DataSource mall, DataSource foundation, PlatformTransactionManager transactions) {}

    @Bean
    MallStorage mallStorage(@Value("${shop.modules.mall.datasource}") String mall,
            @Value("${shop.modules.foundation.datasource}") String foundation, BeanFactory beans) {
        return new MallStorage(beans.getBean(mall + "DataSource", DataSource.class),
                beans.getBean(foundation + "DataSource", DataSource.class),
                beans.getBean(mall + "TxManager", PlatformTransactionManager.class));
    }

    @Bean
    @ConditionalOnMissingBean
    CommerceUnitOfWork mallUnitOfWork(MallStorage storage) {
        return new JdbcCommerceUnitOfWork(storage.mall(), storage.transactions());
    }

    @Bean
    @ConditionalOnMissingBean(CatalogRepository.class)
    JdbcCatalogRepository mallCatalogRepository(MallStorage storage) {
        return new JdbcCatalogRepository(storage.mall(), storage.foundation());
    }

    @Bean
    @ConditionalOnMissingBean(SubscriptionAccountRepository.class)
    JdbcSubscriptionAccountRepository mallSubscriptionAccounts(MallStorage storage) {
        return new JdbcSubscriptionAccountRepository(storage.mall());
    }

    @Bean
    @Lazy
    @ConditionalOnMissingBean
    JdbcEndActivation mallEndActivation(MallStorage storage, @Qualifier("platformDataSource") DataSource platform,
            CommerceIdGenerator ids) {
        return new JdbcEndActivation(storage.mall(), platform, ids);
    }

    @Bean
    @Lazy
    @ConditionalOnMissingBean(SubscriptionRepository.class)
    JdbcSubscriptionRepository mallSubscriptions(MallStorage storage, CommerceIdGenerator ids, JdbcEndActivation activation) {
        return new JdbcSubscriptionRepository(storage.mall(), ids, activation);
    }

    @Bean
    @ConditionalOnProperty(name = "commerce.mall.enabled", havingValue = "true")
    @ConditionalOnMissingBean(MallRepository.class)
    MallRepository mallOrderRepository(MallStorage storage, CommerceEventPersistencePort events, CommerceIdGenerator ids) {
        return new JdbcMallRepository(storage.mall(), events, ids);
    }

    @Bean
    @ConditionalOnProperty(name = "commerce.mall.enabled", havingValue = "true")
    @ConditionalOnMissingBean
    PurchaseOrderQueryPort mallOrderQueries(MallStorage storage) {
        return new JdbcPurchaseOrderQuery(storage.mall(), true);
    }

    @Bean
    @ConditionalOnProperty(name = "commerce.mall.enabled", havingValue = "true")
    @ConditionalOnMissingBean
    FulfillmentRepository mallFulfillmentRepository(MallStorage storage) {
        return new JdbcFulfillmentRepository(storage.mall(), true);
    }

    @Bean
    @ConditionalOnProperty(name = "commerce.mall.enabled", havingValue = "true")
    @ConditionalOnMissingBean
    RefundRepository mallRefunds(MallStorage storage, CommerceIdGenerator ids, SubscriptionRepository subscriptions) {
        return new JdbcRefundRepository(storage.mall(), ids, subscriptions, true);
    }

    @Bean
    @ConditionalOnProperty(prefix = "commerce.mall", name = {"enabled", "payment.enabled", "offline.enabled"}, havingValue = "true")
    @ConditionalOnMissingBean
    OfflineRepository mallOfflineRepository(MallStorage storage) {
        return new JdbcMallOfflineRepository(storage.mall());
    }
}

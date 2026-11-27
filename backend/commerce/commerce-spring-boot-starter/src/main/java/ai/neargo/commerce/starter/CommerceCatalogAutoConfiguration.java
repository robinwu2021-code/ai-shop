package ai.neargo.commerce.starter;

import ai.neargo.commerce.api.catalog.CatalogFacade;
import ai.neargo.commerce.api.port.*;
import ai.neargo.commerce.core.catalog.*;
import ai.neargo.commerce.core.spi.CommerceUnitOfWork;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.Bean;

import ai.neargo.commerce.persistence.JdbcCatalogRepository;
import ai.neargo.commerce.persistence.JdbcCommerceUnitOfWork;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.PlatformTransactionManager;

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
    @ConditionalOnProperty(name = "shop.modules.mall.datasource")
    CatalogSupport mallCatalogSupport(
            @Value("${shop.modules.mall.datasource}") String mallSource,
            @Value("${shop.modules.foundation.datasource}") String foundationSource,
            BeanFactory beans, CatalogAuthorizationPort authorization, BusinessEndReadinessPort readiness,
            CommerceEventPersistencePort events, CommerceIdGenerator ids, ObjectProvider<MoneyPolicyPort> policies) {
        var mall = beans.getBean(mallSource + "DataSource", DataSource.class);
        var foundation = beans.getBean(foundationSource + "DataSource", DataSource.class);
        var transactions = beans.getBean(mallSource + "TxManager", PlatformTransactionManager.class);
        return new CatalogSupport(new JdbcCatalogRepository(mall, foundation),
                new JdbcCommerceUnitOfWork(mall, transactions), authorization, readiness,
                events, Clock.systemUTC(), ids, policies.getIfAvailable(() -> MoneyPolicyPort.CNY_COMPATIBILITY));
    }

    @Bean
    @ConditionalOnMissingBean(CatalogFacade.class)
    CatalogFacade commerceCatalogFacade(
            CatalogRepository repository,
            CommerceUnitOfWork uow,
            CatalogAuthorizationPort authorization,
            BusinessEndReadinessPort readiness,
            CommerceEventPersistencePort events,
            CommerceIdGenerator ids, ObjectProvider<MoneyPolicyPort> policies,
            ObjectProvider<CatalogSupport> mallCatalogSupport) {
        var legacy = new CatalogSupport(
                repository, uow, authorization, readiness, events, Clock.systemUTC(), ids, policies.getIfAvailable(() -> MoneyPolicyPort.CNY_COMPATIBILITY));
        return new DefaultCatalogFacade(legacy, mallCatalogSupport.getIfAvailable());
    }
}

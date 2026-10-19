package ai.neargo.shop.pay.svc;

import ai.neargo.shop.pay.entitlement.port.EntitlementCatalogPortImpl;
import ai.neargo.shop.pay.entitlement.policy.PayEntitlementFeatureGate;
import ai.neargo.shop.pay.entitlement.service.EntitlementCatalogService;
import ai.neargo.shop.spi.entitlement.EntitlementCatalogPort;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** A single pay resource, with the explicit transaction name also used in embedded deployment. */
@Configuration(proxyBeanMethods = false)
public class EntitlementPayBootstrap {
    @Bean(name = {"transactionManager", "payTxManager"})
    DataSourceTransactionManager payTransactions(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }
    @Bean
    EntitlementCatalogPort entitlementCatalogPort(EntitlementCatalogService catalog,
                                                PayEntitlementFeatureGate features) {
        return new EntitlementCatalogPortImpl(catalog, features);
    }
}

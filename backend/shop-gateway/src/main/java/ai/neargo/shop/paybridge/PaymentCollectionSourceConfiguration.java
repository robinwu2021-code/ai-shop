package ai.neargo.shop.paybridge;

import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.transaction.PlatformTransactionManager;

/** Eager initialization completes before outbox SmartLifecycle workers start. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "shop.payment-collections.enabled", havingValue = "true")
@EnableConfigurationProperties(PaymentCollectionSourceProperties.class)
public class PaymentCollectionSourceConfiguration {
    @Bean(initMethod = "provision")
    @Lazy(false)
    @DependsOnDatabaseInitialization
    PaymentCollectionSourceBootstrap paymentCollectionSourceBootstrap(
            PaymentCollectionSourceProperties properties,
            @Qualifier("dataSource") DataSource dataSource,
            @Qualifier("transactionManager") PlatformTransactionManager manager) {
        return new PaymentCollectionSourceBootstrap(properties, dataSource, manager);
    }
}

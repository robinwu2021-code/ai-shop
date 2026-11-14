package ai.neargo.shop.commercebridge;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/**
 * Trusted store directory for commerce. Merchant end-access (api) and the operator mall routes
 * (ops) both read the same authoritative store facts, so it is assembled in both profiles.
 */
@Configuration(proxyBeanMethods = false)
@Profile({"api", "ops"})
@ConditionalOnProperty(name = "commerce.enabled", havingValue = "true")
class CommerceStoreDirectoryConfiguration {
    @Bean
    ShopCommerceStoreAdapter commerceStoreDirectory(
            DataSource ds, PlatformTransactionManager manager) {
        return new ShopCommerceStoreAdapter(ds, manager);
    }
}

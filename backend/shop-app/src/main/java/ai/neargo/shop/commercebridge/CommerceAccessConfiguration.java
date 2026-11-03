package ai.neargo.shop.commercebridge;

import ai.neargo.commerce.api.access.EndAccessFacade;
import ai.neargo.commerce.starter.security.CommerceEndAccessConfiguration;
import ai.neargo.shop.auth.*;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

@Configuration(proxyBeanMethods = false)
@Profile("api")
@ConditionalOnProperty(name = "commerce.enabled", havingValue = "true")
@Import(CommerceEndAccessConfiguration.class)
class CommerceAccessConfiguration {
    @Bean
    ShopCommerceStoreAdapter commerceStoreDirectory(
            DataSource ds, PlatformTransactionManager manager) {
        return new ShopCommerceStoreAdapter(ds, manager);
    }

    @Bean
    ShopCommerceActorAdapter commerceActorAuthority(
            AccountSessionVerifier sessions,
            BizIdentityResolver resolver,
            ShopCommerceStoreAdapter stores) {
        return new ShopCommerceActorAdapter(sessions, resolver, stores);
    }

    @Bean
    ShopCommerceEndContextAdapter commerceAdmission(
            ShopCommerceStoreAdapter stores, BizIdentityResolver resolver, EndAccessFacade access) {
        return new ShopCommerceEndContextAdapter(stores, resolver, access);
    }
}

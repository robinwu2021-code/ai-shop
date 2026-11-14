package ai.neargo.shop.commercebridge;

import ai.neargo.commerce.api.access.EndAccessFacade;
import ai.neargo.commerce.starter.security.CommerceEndAccessConfiguration;
import ai.neargo.shop.auth.*;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;


@Configuration(proxyBeanMethods = false)
@Profile("api")
@ConditionalOnProperty(name = "commerce.enabled", havingValue = "true")
@Import({
    CommerceStoreDirectoryConfiguration.class,
    CommerceEndAccessConfiguration.class,
    ai.neargo.commerce.starter.CommerceFoundationConfiguration.class
})
class CommerceAccessConfiguration {
    @Bean
    ShopCommerceActorAdapter commerceActorAuthority(
            AccountSessionVerifier sessions,
            BizIdentityResolver resolver,
            ShopCommerceStoreAdapter stores) {
        return new ShopCommerceActorAdapter(sessions, resolver, stores);
    }

    @Bean
    ShopCommerceEndContextAdapter commerceAdmission(
            ShopCommerceStoreAdapter stores,
            BizIdentityResolver resolver,
            EndAccessFacade access,
            AccountSessionVerifier sessions,
            org.springframework.beans.factory.ObjectProvider<java.time.Clock> clocks) {
        return new ShopCommerceEndContextAdapter(
                stores,
                resolver,
                access,
                sessions,
                clocks.getIfAvailable(java.time.Clock::systemUTC));
    }
}

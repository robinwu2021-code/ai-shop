package ai.neargo.shop.commercebridge;

import ai.neargo.commerce.core.access.EndAccessService;
import ai.neargo.commerce.core.spi.CommerceUnitOfWork;
import ai.neargo.commerce.persistence.JdbcEndAccessRepository;
import ai.neargo.commerce.starter.security.CommerceEndContextService;
import ai.neargo.shop.auth.*;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.bind.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.*;
import java.util.*;

import javax.sql.DataSource;

@Configuration(proxyBeanMethods = false)
@Profile("api")
@ConditionalOnProperty(name = "commerce.enabled", havingValue = "true")
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
    CommerceEndContextService commerceEndContextCodec(Environment env) {
        var encoded =
                Binder.get(env)
                        .bind("commerce.access.keys", Bindable.mapOf(String.class, String.class))
                        .orElseGet(Map::of);
        var keys = new HashMap<String, byte[]>();
        encoded.forEach((id, value) -> keys.put(id, Base64.getDecoder().decode(value)));
        return new CommerceEndContextService(
                env.getRequiredProperty("commerce.access.active-key-id"), keys);
    }

    @Bean
    EndAccessService commerceEndAccess(
            DataSource ds,
            CommerceUnitOfWork uow,
            ShopCommerceActorAdapter authority,
            CommerceEndContextService codec,
            Environment env,
            ObjectProvider<Clock> clocks) {
        var ttl =
                Binder.get(env)
                        .bind("commerce.access.end-context-ttl", Duration.class)
                        .orElse(Duration.ofMinutes(5));
        return new EndAccessService(
                new JdbcEndAccessRepository(ds),
                uow,
                authority,
                codec,
                clocks.getIfAvailable(Clock::systemUTC),
                ttl);
    }

    @Bean
    ShopCommerceEndContextAdapter commerceAdmission(
            ShopCommerceStoreAdapter stores,
            BizIdentityResolver resolver,
            EndAccessService access) {
        return new ShopCommerceEndContextAdapter(stores, resolver, access);
    }
}

package ai.neargo.commerce.starter.security;

import ai.neargo.commerce.api.access.EndAccessFacade;
import ai.neargo.commerce.api.port.EndAccessAuthorityPort;
import ai.neargo.commerce.core.access.EndAccessService;
import ai.neargo.commerce.core.spi.CommerceUnitOfWork;
import ai.neargo.commerce.persistence.JdbcEndAccessRepository;
import ai.neargo.commerce.persistence.JdbcCommerceUnitOfWork;
import ai.neargo.commerce.persistence.JdbcPlatformEndAccessRepository;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.transaction.PlatformTransactionManager;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.bind.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

import java.time.*;
import java.util.*;

import javax.sql.DataSource;

/**
 * Import when the host enables merchant web access and supplies an authoritative session adapter.
 */
@Configuration(proxyBeanMethods = false)
public class CommerceEndAccessConfiguration {
    @Bean
    CommerceRoutePolicy commerceRoutePolicy() {
        return new CommerceRoutePolicy();
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
    EndAccessFacade commerceEndAccess(
            DataSource ds,
            CommerceUnitOfWork uow,
            EndAccessAuthorityPort authority,
            CommerceEndContextService codec,
            Environment env,
            ObjectProvider<Clock> clocks, BeanFactory beans) {
        var ttl =
                Binder.get(env)
                        .bind("commerce.access.end-context-ttl", Duration.class)
                        .orElse(Duration.ofMinutes(5));
        EndAccessService.Repository grants;
        if (env.containsProperty("shop.modules.mall.datasource")) {
            var platform = beans.getBean("platformDataSource", DataSource.class);
            grants = new JdbcPlatformEndAccessRepository(platform);
            uow = new JdbcCommerceUnitOfWork(platform,
                    beans.getBean("platformTxManager", PlatformTransactionManager.class));
        } else {
            grants = new JdbcEndAccessRepository(ds);
        }
        return new EndAccessService(
                grants,
                uow,
                authority,
                codec,
                clocks.getIfAvailable(Clock::systemUTC),
                ttl);
    }
}

package ai.neargo.shop.commerce.channel;

import ai.neargo.shop.spi.commerce.ChannelCatalogSourceInternalApi;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationInternalApi;
import ai.neargo.shop.svc.ConfigServiceLocator;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.ServiceName;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Lazy;

import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;

/**
 * Local and remote providers cannot shadow each other; original owner registries reject duplicate
 * FOOD registrations.
 */
@AutoConfiguration(beforeName = "ai.neargo.shop.commerce.config.CommerceCoreAutoConfiguration")
@ConditionalOnProperty(name = "shop.commerce.food-source-client.enabled", havingValue = "true")
@EnableConfigurationProperties(ConfigServiceLocator.class)
@Import(InternalHttp.class)
public class CommerceFoodSourceClientAutoConfiguration {
    @Bean
    @Lazy
    ChannelCatalogSourceInternalApi channelCatalogSourceInternalApi(
            InternalHttp http, @Value("${shop.food.scope-key:}") String key) {
        return http.client(
                ServiceName.FOOD,
                ChannelCatalogSourceInternalApi.class,
                Duration.ofSeconds(10),
                new CatalogWorkerScopeInterceptor(key, Clock.systemUTC()));
    }

    @Bean
    @Lazy
    ChannelSourceValidationInternalApi channelSourceValidationInternalApi(
            InternalHttp http, @Value("${shop.food.scope-key:}") String key) {
        return http.client(
                ServiceName.FOOD,
                ChannelSourceValidationInternalApi.class,
                Duration.ofSeconds(5),
                new CatalogWorkerScopeInterceptor(key, Clock.systemUTC()));
    }

    @Bean
    RemoteChannelCatalogSourcePort remoteChannelCatalogSourcePort(
            ObjectProvider<ChannelCatalogSourceInternalApi> api,
            ObjectMapper json,
            ObjectProvider<Clock> clocks) {
        return new RemoteChannelCatalogSourcePort(
                api::getObject, json, clocks.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    RemoteChannelSourceValidationProvider remoteChannelSourceValidationProvider(
            ObjectProvider<ChannelSourceValidationInternalApi> api, ObjectProvider<Clock> clocks) {
        return new RemoteChannelSourceValidationProvider(
                api::getObject, clocks.getIfAvailable(Clock::systemUTC));
    }
}

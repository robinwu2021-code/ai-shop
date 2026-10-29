package ai.neargo.shop.commerce.channel;

import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;
import ai.neargo.shop.spi.commerce.StoreBusinessAvailabilityInternalApi;
import ai.neargo.shop.svc.ConfigServiceLocator;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.ServiceName;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Lazy;

import java.time.Clock;
import java.time.Duration;

/**
 * Remote contributions require explicit owner-specific flags; duplicate embedded owners remain an
 * availability registry error.
 */
@AutoConfiguration(
        beforeName = "ai.neargo.shop.commerce.config.CommerceAvailabilityAutoConfiguration")
@EnableConfigurationProperties(ConfigServiceLocator.class)
@Import(InternalHttp.class)
public class CommerceStoreBusinessAvailabilityClientAutoConfiguration {
    @Bean
    @Lazy
    @ConditionalOnProperty(
            name = "shop.commerce.food-store-availability-client.enabled",
            havingValue = "true")
    StoreBusinessAvailabilityInternalApi foodStoreBusinessAvailabilityInternalApi(
            InternalHttp http, @Value("${shop.food.scope-key:}") String key) {
        return http.client(
                ServiceName.FOOD,
                StoreBusinessAvailabilityInternalApi.class,
                Duration.ofSeconds(5),
                new CatalogWorkerScopeInterceptor(key, Clock.systemUTC()));
    }

    @Bean
    @ConditionalOnProperty(
            name = "shop.commerce.food-store-availability-client.enabled",
            havingValue = "true")
    RemoteStoreBusinessAvailabilitySource remoteFoodStoreBusinessAvailabilitySource(
            @Qualifier("foodStoreBusinessAvailabilityInternalApi")
                    ObjectProvider<StoreBusinessAvailabilityInternalApi> api,
            ObjectProvider<Clock> clocks) {
        return new RemoteStoreBusinessAvailabilitySource(
                Owner.FOOD, api::getObject, clocks.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    @Lazy
    @ConditionalOnProperty(
            name = "shop.commerce.retail-store-availability-client.enabled",
            havingValue = "true")
    StoreBusinessAvailabilityInternalApi retailStoreBusinessAvailabilityInternalApi(
            InternalHttp http, @Value("${shop.retail.scope-key:}") String key) {
        return http.client(
                ServiceName.PLATFORM,
                StoreBusinessAvailabilityInternalApi.class,
                Duration.ofSeconds(5),
                new RetailOrderScopeInterceptor(key, Clock.systemUTC()));
    }

    @Bean
    @ConditionalOnProperty(
            name = "shop.commerce.retail-store-availability-client.enabled",
            havingValue = "true")
    RemoteStoreBusinessAvailabilitySource remoteRetailStoreBusinessAvailabilitySource(
            @Qualifier("retailStoreBusinessAvailabilityInternalApi")
                    ObjectProvider<StoreBusinessAvailabilityInternalApi> api,
            ObjectProvider<Clock> clocks) {
        return new RemoteStoreBusinessAvailabilitySource(
                Owner.RETAIL, api::getObject, clocks.getIfAvailable(Clock::systemUTC));
    }
}

package ai.neargo.shop.food.commerce.config;

import ai.neargo.shop.food.commerce.channel.FoodFulfillmentSignalScopeInterceptor;
import ai.neargo.shop.food.commerce.port.RemoteDiningChannelFulfillmentSignalPort;
import ai.neargo.shop.food.spi.DiningChannelFulfillmentSignalInternalApi;
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

import java.time.Clock;
import java.time.Duration;

/**
 * Explicit remote mode calls FOOD only; conflicting embedded and remote signal owners are not
 * silently substituted.
 */
@AutoConfiguration
@ConditionalOnProperty(name = "shop.food.fulfillment-signal-client.enabled", havingValue = "true")
@EnableConfigurationProperties(ConfigServiceLocator.class)
@Import(InternalHttp.class)
public class FoodFulfillmentSignalClientAutoConfiguration {
    @Bean
    @Lazy
    DiningChannelFulfillmentSignalInternalApi diningChannelFulfillmentSignalInternalApi(
            InternalHttp http,
            @Value("${shop.food.scope-key:}") String key,
            ObjectProvider<Clock> clocks) {
        return http.client(
                ServiceName.FOOD,
                DiningChannelFulfillmentSignalInternalApi.class,
                Duration.ofSeconds(5),
                new FoodFulfillmentSignalScopeInterceptor(
                        key, clocks.getIfAvailable(Clock::systemUTC)));
    }

    @Bean
    RemoteDiningChannelFulfillmentSignalPort remoteDiningChannelFulfillmentSignalPort(
            ObjectProvider<DiningChannelFulfillmentSignalInternalApi> api,
            ObjectProvider<Clock> clocks) {
        return new RemoteDiningChannelFulfillmentSignalPort(
                api::getObject, clocks.getIfAvailable(Clock::systemUTC));
    }
}

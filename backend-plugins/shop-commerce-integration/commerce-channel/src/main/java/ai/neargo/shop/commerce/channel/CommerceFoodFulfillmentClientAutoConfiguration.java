package ai.neargo.shop.commerce.channel;

import ai.neargo.shop.spi.commerce.ChannelFulfillmentSourceInternalApi;
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
 * Explicit standalone transport contributes the same original FOOD custody owner without enabling
 * supplier writes.
 */
@AutoConfiguration(beforeName = "ai.neargo.shop.commerce.config.CommerceCoreAutoConfiguration")
@ConditionalOnProperty(name = "shop.commerce.food-fulfillment-client.enabled", havingValue = "true")
@EnableConfigurationProperties(ConfigServiceLocator.class)
@Import(InternalHttp.class)
public class CommerceFoodFulfillmentClientAutoConfiguration {
    @Bean
    @Lazy
    ChannelFulfillmentSourceInternalApi channelFulfillmentSourceInternalApi(
            InternalHttp http,
            @Value("${shop.food.scope-key:}") String key,
            ObjectProvider<Clock> clocks) {
        return http.client(
                ServiceName.FOOD,
                ChannelFulfillmentSourceInternalApi.class,
                Duration.ofSeconds(5),
                new CatalogWorkerScopeInterceptor(key, clocks.getIfAvailable(Clock::systemUTC)));
    }

    @Bean
    RemoteFoodChannelFulfillmentSourcePort remoteFoodChannelFulfillmentSourcePort(
            ObjectProvider<ChannelFulfillmentSourceInternalApi> api, ObjectProvider<Clock> clocks) {
        return new RemoteFoodChannelFulfillmentSourcePort(
                api::getObject, clocks.getIfAvailable(Clock::systemUTC));
    }
}

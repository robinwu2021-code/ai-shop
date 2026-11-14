package ai.neargo.shop.commerce.channel;

import ai.neargo.shop.spi.commerce.ChannelOrderIngestInternalApi;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourceInternalApi;
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

/** Explicit remote registration must not be enabled together with an embedded FOOD owner. */
@AutoConfiguration(beforeName = "ai.neargo.shop.commerce.config.CommerceCoreAutoConfiguration")
@ConditionalOnProperty(name = "shop.commerce.food-order-client.enabled", havingValue = "true")
@EnableConfigurationProperties(ConfigServiceLocator.class)
@Import(InternalHttp.class)
public class CommerceFoodOrderClientAutoConfiguration {
    @Bean
    @Lazy
    ChannelOrderIngestInternalApi channelOrderIngestInternalApi(
            InternalHttp http, @Value("${shop.food.scope-key:}") String key) {
        return http.client(
                ServiceName.FOOD,
                ChannelOrderIngestInternalApi.class,
                Duration.ofSeconds(5),
                new CatalogWorkerScopeInterceptor(key, Clock.systemUTC()));
    }

    @Bean
    @Lazy
    IndustryChannelOrderSourceInternalApi industryChannelOrderSourceInternalApi(
            InternalHttp http, @Value("${shop.food.scope-key:}") String key) {
        return http.client(
                ServiceName.FOOD,
                IndustryChannelOrderSourceInternalApi.class,
                Duration.ofSeconds(5),
                new CatalogWorkerScopeInterceptor(key, Clock.systemUTC()));
    }

    @Bean
    RemoteFoodChannelOrderIngestPort remoteFoodChannelOrderIngestPort(
            ObjectProvider<ChannelOrderIngestInternalApi> api) {
        return new RemoteFoodChannelOrderIngestPort(api::getObject);
    }

    @Bean
    RemoteFoodChannelOrderSourcePort remoteFoodChannelOrderSourcePort(
            ObjectProvider<IndustryChannelOrderSourceInternalApi> api,
            ObjectProvider<Clock> clocks) {
        return new RemoteFoodChannelOrderSourcePort(
                api::getObject, clocks.getIfAvailable(Clock::systemUTC));
    }
}

package ai.neargo.shop.commerce.channel;

import ai.neargo.shop.spi.commerce.ChannelFulfillmentTriggerInternalApi;
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

/** Explicit standalone mode never silently replaces an embedded original trigger owner. */
@AutoConfiguration
@ConditionalOnProperty(
        name = "shop.commerce.fulfillment-trigger-client.enabled",
        havingValue = "true")
@EnableConfigurationProperties(ConfigServiceLocator.class)
@Import(InternalHttp.class)
public class CommerceFulfillmentTriggerClientAutoConfiguration {
    @Bean
    @Lazy
    ChannelFulfillmentTriggerInternalApi channelFulfillmentTriggerInternalApi(
            InternalHttp http,
            @Value("${shop.food.scope-key:}") String key,
            ObjectProvider<Clock> clocks) {
        return http.client(
                ServiceName.COMMERCE,
                ChannelFulfillmentTriggerInternalApi.class,
                Duration.ofSeconds(5),
                new FulfillmentTriggerScopeInterceptor(
                        key, clocks.getIfAvailable(Clock::systemUTC)));
    }

    @Bean
    RemoteChannelFulfillmentTriggerPort remoteChannelFulfillmentTriggerPort(
            ObjectProvider<ChannelFulfillmentTriggerInternalApi> api) {
        return new RemoteChannelFulfillmentTriggerPort(api::getObject);
    }
}

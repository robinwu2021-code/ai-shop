package ai.neargo.shop.commerce.channel;

import ai.neargo.shop.spi.commerce.RetailChannelOrderIngestInternalApi;
import ai.neargo.shop.spi.trade.RetailChannelOrderSourceInternalApi;
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
 * Explicit remote mode addresses PLATFORM's original retail owner; enabling it with a local owner
 * remains a registry error.
 */
@AutoConfiguration(beforeName = "ai.neargo.shop.commerce.config.CommerceCoreAutoConfiguration")
@ConditionalOnProperty(name = "shop.commerce.retail-order-client.enabled", havingValue = "true")
@EnableConfigurationProperties(ConfigServiceLocator.class)
@Import(InternalHttp.class)
public class CommerceRetailOrderClientAutoConfiguration {
    @Bean
    @Lazy
    RetailChannelOrderIngestInternalApi retailChannelOrderIngestInternalApi(
            InternalHttp http, @Value("${shop.retail.scope-key:}") String key) {
        return http.client(
                ServiceName.PLATFORM,
                RetailChannelOrderIngestInternalApi.class,
                Duration.ofSeconds(5),
                new RetailOrderScopeInterceptor(key, Clock.systemUTC()));
    }

    @Bean
    @Lazy
    RetailChannelOrderSourceInternalApi retailChannelOrderSourceInternalApi(
            InternalHttp http, @Value("${shop.retail.scope-key:}") String key) {
        return http.client(
                ServiceName.PLATFORM,
                RetailChannelOrderSourceInternalApi.class,
                Duration.ofSeconds(5),
                new RetailOrderScopeInterceptor(key, Clock.systemUTC()));
    }

    @Bean
    RemoteRetailChannelOrderIngestPort remoteRetailChannelOrderIngestPort(
            ObjectProvider<RetailChannelOrderIngestInternalApi> api) {
        return new RemoteRetailChannelOrderIngestPort(api::getObject);
    }

    @Bean
    RemoteRetailChannelOrderSourcePort remoteRetailChannelOrderSourcePort(
            ObjectProvider<RetailChannelOrderSourceInternalApi> api, ObjectProvider<Clock> clocks) {
        return new RemoteRetailChannelOrderSourcePort(
                api::getObject, clocks.getIfAvailable(Clock::systemUTC));
    }
}

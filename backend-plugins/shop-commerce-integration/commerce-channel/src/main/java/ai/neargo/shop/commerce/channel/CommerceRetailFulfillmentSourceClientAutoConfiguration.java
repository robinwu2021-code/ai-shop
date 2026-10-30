package ai.neargo.shop.commerce.channel;

import ai.neargo.shop.spi.commerce.OriginalRetailChannelFulfillmentSourceAdapter;
import ai.neargo.shop.spi.trade.IndustryChannelFulfillmentSourceInternalApi;
import ai.neargo.shop.svc.ConfigServiceLocator;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCalls;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Lazy;

import java.time.Clock;
import java.time.Duration;

/** The independent commerce process reads the original primary owner, not a remote supplier ACK. */
@AutoConfiguration
@ConditionalOnProperty(
        name = "shop.commerce.retail-fulfillment.source-mode",
        havingValue = "remote")
@EnableConfigurationProperties(ConfigServiceLocator.class)
@Import(InternalHttp.class)
public class CommerceRetailFulfillmentSourceClientAutoConfiguration {
    @Bean
    @Lazy
    IndustryChannelFulfillmentSourceInternalApi industryChannelFulfillmentSourceInternalApi(
            InternalHttp http) {
        return http.client(
                ServiceName.PLATFORM,
                IndustryChannelFulfillmentSourceInternalApi.class,
                Duration.ofSeconds(5));
    }

    @Bean
    @ConditionalOnProperty(
            name = {"shop.commerce.enabled", "shop.retail.commerce.fulfillment.enabled"},
            havingValue = "true")
    OriginalRetailChannelFulfillmentSourceAdapter remoteRetailChannelFulfillmentSource(
            ObjectProvider<IndustryChannelFulfillmentSourceInternalApi> api,
            ObjectProvider<Clock> clocks) {
        return new OriginalRetailChannelFulfillmentSourceAdapter(
                query -> ServiceCalls.call(ServiceName.PLATFORM, () -> api.getObject().read(query)),
                clocks.getIfAvailable(Clock::systemUTC));
    }
}

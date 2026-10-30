package ai.neargo.shop.commerce.channel;

import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;
import ai.neargo.shop.spi.commerce.OriginalChannelAfterSaleSourceAdapter;
import ai.neargo.shop.spi.trade.IndustryChannelAfterSaleInternalApi;
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

@AutoConfiguration(beforeName = "ai.neargo.shop.commerce.config.CommerceAfterSaleAutoConfiguration")
@ConditionalOnProperty(name = "shop.commerce.after-sale.source-mode", havingValue = "remote")
@EnableConfigurationProperties(ConfigServiceLocator.class)
@Import(InternalHttp.class)
public class CommerceAfterSaleSourceClientAutoConfiguration {
    @Bean
    @Lazy
    IndustryChannelAfterSaleInternalApi industryChannelAfterSaleInternalApi(InternalHttp http) {
        return http.client(
                ServiceName.PLATFORM,
                IndustryChannelAfterSaleInternalApi.class,
                Duration.ofSeconds(5));
    }

    @Bean
    OriginalChannelAfterSaleSourceAdapter remoteFoodAfterSaleSource(
            ObjectProvider<IndustryChannelAfterSaleInternalApi> api, ObjectProvider<Clock> clocks) {
        return new OriginalChannelAfterSaleSourceAdapter(
                Owner.FOOD,
                query -> ServiceCalls.call(ServiceName.PLATFORM, () -> api.getObject().read(query)),
                clocks.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    OriginalChannelAfterSaleSourceAdapter remoteRetailAfterSaleSource(
            ObjectProvider<IndustryChannelAfterSaleInternalApi> api, ObjectProvider<Clock> clocks) {
        return new OriginalChannelAfterSaleSourceAdapter(
                Owner.RETAIL,
                query -> ServiceCalls.call(ServiceName.PLATFORM, () -> api.getObject().read(query)),
                clocks.getIfAvailable(Clock::systemUTC));
    }
}

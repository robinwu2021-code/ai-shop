package ai.neargo.shop.food.channel.config;

import ai.neargo.shop.food.channel.port.CoreIndustryChannelOrderPortImpl;
import ai.neargo.shop.food.channel.port.CoreIndustryCheckoutPortImpl;
import ai.neargo.shop.food.channel.port.CoreIndustryFulfillmentPortImpl;
import ai.neargo.shop.food.channel.port.CoreIndustryOrderAmendmentPortImpl;
import ai.neargo.shop.food.channel.port.CoreIndustryOrderPortImpl;
import ai.neargo.shop.food.channel.port.CorePricingCheckoutPortImpl;
import ai.neargo.shop.spi.trade.IndustryChannelOrderInternalApi;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort;
import ai.neargo.shop.spi.trade.IndustryCheckoutInternalApi;
import ai.neargo.shop.spi.trade.IndustryCheckoutPort;
import ai.neargo.shop.spi.trade.IndustryFulfillmentInternalApi;
import ai.neargo.shop.spi.trade.IndustryFulfillmentPort;
import ai.neargo.shop.spi.trade.IndustryOrderAmendmentInternalApi;
import ai.neargo.shop.spi.trade.IndustryOrderAmendmentPort;
import ai.neargo.shop.spi.trade.IndustryOrderInternalApi;
import ai.neargo.shop.spi.trade.IndustryOrderPort;
import ai.neargo.shop.spi.trade.PricingCheckoutInternalApi;
import ai.neargo.shop.spi.trade.PricingCheckoutPort;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.ServiceName;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import java.time.Duration;

/** Explicit food-server import; embedded deployments retain the local core port. */
@Configuration(proxyBeanMethods = false)
public class FoodPricingClientConfiguration {
    @Bean
    @Lazy
    @ConditionalOnMissingBean(IndustryChannelOrderInternalApi.class)
    IndustryChannelOrderInternalApi industryChannelOrderInternalApi(InternalHttp http) {
        return http.client(
                ServiceName.PLATFORM, IndustryChannelOrderInternalApi.class, Duration.ofSeconds(5));
    }

    @Bean
    @ConditionalOnMissingBean(IndustryChannelOrderPort.class)
    IndustryChannelOrderPort coreIndustryChannelOrderPort(
            ObjectProvider<IndustryChannelOrderInternalApi> api) {
        return new CoreIndustryChannelOrderPortImpl(api::getObject);
    }

    @Bean
    @Lazy
    IndustryOrderAmendmentInternalApi industryOrderAmendmentInternalApi(InternalHttp http) {
        return http.client(
                ServiceName.PLATFORM,
                IndustryOrderAmendmentInternalApi.class,
                Duration.ofSeconds(5));
    }

    @Bean
    @ConditionalOnMissingBean(IndustryOrderAmendmentPort.class)
    IndustryOrderAmendmentPort coreIndustryOrderAmendmentPort(
            ObjectProvider<IndustryOrderAmendmentInternalApi> api) {
        return new CoreIndustryOrderAmendmentPortImpl(api::getObject);
    }

    @Bean
    @Lazy
    IndustryFulfillmentInternalApi industryFulfillmentInternalApi(InternalHttp http) {
        return http.client(
                ServiceName.PLATFORM, IndustryFulfillmentInternalApi.class, Duration.ofSeconds(5));
    }

    @Bean
    @ConditionalOnMissingBean(IndustryFulfillmentPort.class)
    IndustryFulfillmentPort coreIndustryFulfillmentPort(
            ObjectProvider<IndustryFulfillmentInternalApi> api) {
        return new CoreIndustryFulfillmentPortImpl(api::getObject);
    }

    @Bean
    @Lazy
    IndustryCheckoutInternalApi industryCheckoutInternalApi(InternalHttp http) {
        return http.client(
                ServiceName.PLATFORM, IndustryCheckoutInternalApi.class, Duration.ofSeconds(5));
    }

    @Bean
    @ConditionalOnMissingBean(IndustryCheckoutPort.class)
    IndustryCheckoutPort coreIndustryCheckoutPort(ObjectProvider<IndustryCheckoutInternalApi> api) {
        return new CoreIndustryCheckoutPortImpl(api::getObject);
    }

    @Bean
    @Lazy
    IndustryOrderInternalApi industryOrderInternalApi(InternalHttp http) {
        return http.client(
                ServiceName.PLATFORM, IndustryOrderInternalApi.class, Duration.ofSeconds(5));
    }

    @Bean
    @ConditionalOnMissingBean(IndustryOrderPort.class)
    IndustryOrderPort coreIndustryOrderPort(ObjectProvider<IndustryOrderInternalApi> api) {
        return new CoreIndustryOrderPortImpl(api::getObject);
    }

    @Bean
    @Lazy
    PricingCheckoutInternalApi pricingCheckoutInternalApi(InternalHttp http) {
        return http.client(
                ServiceName.PLATFORM, PricingCheckoutInternalApi.class, Duration.ofSeconds(5));
    }

    @Bean
    @ConditionalOnMissingBean(PricingCheckoutPort.class)
    PricingCheckoutPort corePricingCheckoutPort(ObjectProvider<PricingCheckoutInternalApi> api) {
        return new CorePricingCheckoutPortImpl(api::getObject);
    }
}

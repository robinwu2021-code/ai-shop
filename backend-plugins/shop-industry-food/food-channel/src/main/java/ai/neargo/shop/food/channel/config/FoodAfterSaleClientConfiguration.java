package ai.neargo.shop.food.channel.config;

import ai.neargo.shop.food.channel.port.CoreIndustryAfterSalePortImpl;
import ai.neargo.shop.spi.trade.IndustryAfterSaleInternalApi;
import ai.neargo.shop.spi.trade.IndustryAfterSalePort;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.ServiceName;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/** Standalone FOOD imports the real original PLATFORM after-sale owner; embedded retains the local owner. */
@Configuration(proxyBeanMethods = false)
public class FoodAfterSaleClientConfiguration {
    @Bean @Lazy IndustryAfterSaleInternalApi industryAfterSaleInternalApi(InternalHttp http) {
        return http.client(ServiceName.PLATFORM, IndustryAfterSaleInternalApi.class, Duration.ofSeconds(5));
    }
    @Bean @ConditionalOnMissingBean(IndustryAfterSalePort.class)
    IndustryAfterSalePort coreIndustryAfterSalePort(ObjectProvider<IndustryAfterSaleInternalApi> api) {
        return new CoreIndustryAfterSalePortImpl(api::getObject);
    }
}

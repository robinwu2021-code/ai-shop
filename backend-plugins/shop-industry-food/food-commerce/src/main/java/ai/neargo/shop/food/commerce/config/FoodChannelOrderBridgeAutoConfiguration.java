package ai.neargo.shop.food.commerce.config;

import ai.neargo.shop.food.commerce.port.FoodChannelOrderIngestPortImpl;
import ai.neargo.shop.food.commerce.port.FoodIndustryChannelOrderSourcePortImpl;
import ai.neargo.shop.food.service.DiningChannelOrderService;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Explicit bridge owns industry registration; unavailable original evidence never yields a ready
 * preparation.
 */
@AutoConfiguration(afterName = "ai.neargo.shop.food.config.FoodChannelOrderAutoConfiguration")
@ConditionalOnProperty(
        name = {"shop.food.enabled", "shop.food.channel.enabled"},
        havingValue = "true")
@ConditionalOnBean(DiningChannelOrderService.class)
public class FoodChannelOrderBridgeAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(FoodChannelOrderIngestPortImpl.class)
    FoodChannelOrderIngestPortImpl foodChannelOrderIngestPort(DiningChannelOrderService service) {
        return new FoodChannelOrderIngestPortImpl(service);
    }

    @Bean
    @ConditionalOnMissingBean(FoodIndustryChannelOrderSourcePortImpl.class)
    FoodIndustryChannelOrderSourcePortImpl foodIndustryChannelOrderSourcePort(
            ObjectProvider<DiningChannelOrderService> service) {
        return new FoodIndustryChannelOrderSourcePortImpl(service::getObject);
    }
}

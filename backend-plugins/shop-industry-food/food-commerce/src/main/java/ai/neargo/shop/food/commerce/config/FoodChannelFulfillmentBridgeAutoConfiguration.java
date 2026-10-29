package ai.neargo.shop.food.commerce.config;

import ai.neargo.shop.food.commerce.port.FoodChannelFulfillmentSourcePortImpl;
import ai.neargo.shop.food.service.DiningChannelFulfillmentService;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(afterName = "ai.neargo.shop.food.config.FoodChannelFulfillmentAutoConfiguration")
@ConditionalOnProperty(
        name = {"shop.food.enabled", "shop.food.channel.enabled"},
        havingValue = "true")
public class FoodChannelFulfillmentBridgeAutoConfiguration {
    @Bean
    @ConditionalOnBean(DiningChannelFulfillmentService.class)
    @ConditionalOnMissingBean(FoodChannelFulfillmentSourcePortImpl.class)
    FoodChannelFulfillmentSourcePortImpl foodChannelFulfillmentSourcePort(
            DiningChannelFulfillmentService owner) {
        return new FoodChannelFulfillmentSourcePortImpl(owner);
    }
}

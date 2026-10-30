package ai.neargo.shop.food.commerce.config;

import ai.neargo.shop.food.commerce.api.internal.FoodCommerceInternalSecurity;
import ai.neargo.shop.food.commerce.api.internal.InternalFoodChannelFulfillmentSignalController;
import ai.neargo.shop.food.service.DiningChannelFulfillmentSignalPort;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

import tools.jackson.databind.ObjectMapper;

/**
 * The same original FOOD service-token/SYSTEM security guards the opt-in signal route in embedded
 * and standalone hosts.
 */
@AutoConfiguration(
        afterName = {
            "ai.neargo.shop.food.commerce.config.FoodCatalogInternalAutoConfiguration",
            "ai.neargo.shop.food.config.FoodChannelFulfillmentSignalAutoConfiguration"
        })
@ConditionalOnProperty(
        name = {
            "shop.food.commerce.internal.enabled",
            "shop.food.commerce.fulfillment-events.enabled"
        },
        havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class FoodFulfillmentSignalInternalAutoConfiguration {
    @Bean
    @ConditionalOnBean({
        FoodCommerceInternalSecurity.class,
        DiningChannelFulfillmentSignalPort.class
    })
    InternalFoodChannelFulfillmentSignalController internalFoodChannelFulfillmentSignalController(
            FoodCommerceInternalSecurity security,
            DiningChannelFulfillmentSignalPort signals,
            ObjectMapper json) {
        return new InternalFoodChannelFulfillmentSignalController(security, signals, json);
    }
}

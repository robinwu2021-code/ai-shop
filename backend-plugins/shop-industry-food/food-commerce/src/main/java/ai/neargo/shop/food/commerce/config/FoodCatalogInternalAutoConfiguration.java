package ai.neargo.shop.food.commerce.config;

import ai.neargo.shop.food.commerce.api.internal.FoodCommerceInternalSecurity;
import ai.neargo.shop.food.commerce.api.internal.InternalFoodCatalogSourceController;
import ai.neargo.shop.food.commerce.api.internal.InternalFoodChannelFulfillmentController;
import ai.neargo.shop.food.commerce.api.internal.InternalFoodChannelOrderController;
import ai.neargo.shop.food.commerce.api.internal.InternalFoodSourceValidationController;
import ai.neargo.shop.food.commerce.port.FoodCatalogSourcePortImpl;
import ai.neargo.shop.food.commerce.port.FoodChannelFulfillmentSourcePortImpl;
import ai.neargo.shop.food.commerce.port.FoodChannelOrderIngestPortImpl;
import ai.neargo.shop.food.commerce.port.FoodIndustryChannelOrderSourcePortImpl;
import ai.neargo.shop.food.commerce.port.FoodSourceValidationProviderImpl;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

import tools.jackson.databind.ObjectMapper;

import java.time.Clock;

/**
 * The same bounded internal routes run in embedded and independent FOOD without widening the host
 * security chain.
 */
@AutoConfiguration(
        afterName = {
            "ai.neargo.shop.food.commerce.config.FoodCatalogSourceAutoConfiguration",
            "ai.neargo.shop.food.commerce.config.FoodSourceValidationAutoConfiguration",
            "ai.neargo.shop.food.commerce.config.FoodChannelOrderBridgeAutoConfiguration",
            "ai.neargo.shop.food.commerce.config.FoodChannelFulfillmentBridgeAutoConfiguration"
        })
@ConditionalOnProperty(name = "shop.food.commerce.internal.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class FoodCatalogInternalAutoConfiguration {
    @Bean
    FoodCommerceInternalSecurity foodCommerceInternalSecurity(
            @Value("${shop.services.internal-token:}") String token,
            @Value("${shop.food.scope-key:}") String key,
            @Value("${shop.food.scope-key-previous:}") String previous,
            ObjectProvider<Clock> clocks) {
        return new FoodCommerceInternalSecurity(
                token, key, previous, clocks.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    @ConditionalOnBean(FoodCatalogSourcePortImpl.class)
    InternalFoodCatalogSourceController internalFoodCatalogSourceController(
            FoodCommerceInternalSecurity security,
            FoodCatalogSourcePortImpl owner,
            ObjectMapper json,
            ObjectProvider<Clock> clocks) {
        return new InternalFoodCatalogSourceController(
                security, owner, json, clocks.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    @ConditionalOnBean(FoodSourceValidationProviderImpl.class)
    InternalFoodSourceValidationController internalFoodSourceValidationController(
            FoodCommerceInternalSecurity security,
            FoodSourceValidationProviderImpl owner,
            ObjectMapper json,
            ObjectProvider<Clock> clocks) {
        return new InternalFoodSourceValidationController(
                security, owner, json, clocks.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    @ConditionalOnBean({
        FoodChannelOrderIngestPortImpl.class,
        FoodIndustryChannelOrderSourcePortImpl.class
    })
    InternalFoodChannelOrderController internalFoodChannelOrderController(
            FoodCommerceInternalSecurity security,
            FoodChannelOrderIngestPortImpl orders,
            FoodIndustryChannelOrderSourcePortImpl source,
            ObjectMapper json) {
        return new InternalFoodChannelOrderController(security, orders, source, json);
    }

    @Bean
    @ConditionalOnBean(FoodChannelFulfillmentSourcePortImpl.class)
    InternalFoodChannelFulfillmentController internalFoodChannelFulfillmentController(
            FoodCommerceInternalSecurity security,
            FoodChannelFulfillmentSourcePortImpl owner,
            ObjectMapper json) {
        return new InternalFoodChannelFulfillmentController(security, owner, json);
    }
}

package ai.neargo.shop.food.commerce.config;

import ai.neargo.shop.food.commerce.api.internal.FoodCommerceInternalSecurity;
import ai.neargo.shop.food.commerce.api.internal.InternalFoodStoreBusinessAvailabilityController;
import ai.neargo.shop.food.commerce.port.FoodStoreBusinessAvailabilitySource;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

import tools.jackson.databind.ObjectMapper;

/**
 * The real original calendar source is required in either embedded or FOOD standalone deployment.
 */
@AutoConfiguration(
        after = {
            FoodStoreBusinessAvailabilityAutoConfiguration.class,
            FoodCatalogInternalAutoConfiguration.class
        })
@ConditionalOnProperty(name = "shop.food.commerce.internal.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class FoodStoreBusinessAvailabilityInternalAutoConfiguration {
    @Bean
    @ConditionalOnBean({
        FoodStoreBusinessAvailabilitySource.class,
        FoodCommerceInternalSecurity.class,
        ObjectMapper.class
    })
    InternalFoodStoreBusinessAvailabilityController internalFoodStoreBusinessAvailabilityController(
            FoodCommerceInternalSecurity security,
            FoodStoreBusinessAvailabilitySource source,
            ObjectMapper json) {
        return new InternalFoodStoreBusinessAvailabilityController(security, source, json);
    }
}

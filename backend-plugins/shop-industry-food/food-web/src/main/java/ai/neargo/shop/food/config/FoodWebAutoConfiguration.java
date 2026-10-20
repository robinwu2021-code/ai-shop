package ai.neargo.shop.food.config;

import ai.neargo.shop.food.api.FoodRequestScopeValidator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;

/** 餐饮模块装配入口。 */
@AutoConfiguration
@ConditionalOnFoodWebEnabled
@Profile({"api", "ops"})
public class FoodWebAutoConfiguration {
    @Bean
    FoodRequestScopeValidator foodRequestScopeValidator() {
        return new FoodRequestScopeValidator();
    }
}

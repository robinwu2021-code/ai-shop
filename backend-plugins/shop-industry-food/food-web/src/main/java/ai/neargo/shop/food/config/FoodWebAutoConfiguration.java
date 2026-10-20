package ai.neargo.shop.food.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Profile;

/** 餐饮模块装配入口。 */
@AutoConfiguration
@ConditionalOnFoodWebEnabled
@Profile({"api", "ops"})
public class FoodWebAutoConfiguration {
}

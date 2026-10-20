package ai.neargo.shop.food.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/** 餐饮模块装配入口。 */
@AutoConfiguration(afterName = {"org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
        "com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration"})
@Import({FoodPersistenceConfiguration.class, FoodWriteGuardConfiguration.class})
@ConditionalOnFoodEnabled
@EnableConfigurationProperties(FoodProperties.class)
public class FoodAutoConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "shop.food.migration", name = "mode", havingValue = "off")
    FoodSchemaReady foodSchemaOff() { return FoodSchemaReady.off(); }

}

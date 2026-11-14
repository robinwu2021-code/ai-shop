package ai.neargo.shop.food.config;

import ai.neargo.shop.food.api.FoodRequestScopeValidator;
import ai.neargo.shop.food.api.biz.BizServiceEntitlementController;
import ai.neargo.shop.food.foodclient.FoodServiceEntitlementClientConfiguration;
import ai.neargo.shop.food.service.ServiceEntitlementService;
import ai.neargo.shop.spi.user.StoreDirectoryPort;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;

/**
 * Local and typed FOOD deployment expose identical staff methods only when the new operational
 * feature is enabled.
 */
@AutoConfiguration(
        afterName = {
            "ai.neargo.shop.food.config.FoodWebAutoConfiguration",
            "ai.neargo.shop.food.config.FoodServiceEntitlementAutoConfiguration"
        })
@ConditionalOnFoodWebEnabled
@Conditional(FoodWebAutoConfiguration.PolicyEnabled.class)
@ConditionalOnProperty(name = "shop.food.service-entitlements.enabled", havingValue = "true")
@Import(FoodServiceEntitlementClientConfiguration.class)
public class FoodServiceEntitlementWebAutoConfiguration {
    @Bean
    @Profile("api")
    @ConditionalOnBean({
        ServiceEntitlementService.class,
        StoreDirectoryPort.class,
        FoodRequestScopeValidator.class
    })
    @ConditionalOnMissingBean(BizServiceEntitlementController.class)
    BizServiceEntitlementController serviceEntitlementController(
            ServiceEntitlementService service,
            StoreDirectoryPort stores,
            FoodRequestScopeValidator validator) {
        return new BizServiceEntitlementController(service, stores, validator);
    }
}

package ai.neargo.shop.food.config;

import ai.neargo.shop.food.api.FoodRequestScopeValidator;
import ai.neargo.shop.food.api.biz.BizServiceCalendarController;
import ai.neargo.shop.food.api.biz.BizStoreOperationPolicyController;
import ai.neargo.shop.food.foodclient.FoodClientConfiguration;
import ai.neargo.shop.food.service.StoreOperationService;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** 餐饮模块装配入口。 */
@AutoConfiguration(afterName = "ai.neargo.shop.food.config.FoodAutoConfiguration")
@Import(FoodClientConfiguration.class)
@ConditionalOnFoodWebEnabled
@Profile({"api", "ops"})
public class FoodWebAutoConfiguration {
    @Bean
    @Profile("api")
    @Conditional(PolicyEnabled.class)
    @ConditionalOnBean({StoreOperationService.class, StoreDirectoryPort.class})
    @ConditionalOnMissingBean(BizStoreOperationPolicyController.class)
    BizStoreOperationPolicyController policyController(StoreOperationService service, StoreDirectoryPort stores,
            FoodRequestScopeValidator scope) {
        return new BizStoreOperationPolicyController(service, stores, scope);
    }

    @Bean
    @Profile("api")
    @Conditional(PolicyEnabled.class)
    @ConditionalOnBean({StoreOperationService.class, StoreDirectoryPort.class})
    @ConditionalOnMissingBean(BizServiceCalendarController.class)
    BizServiceCalendarController calendarController(StoreOperationService service, StoreDirectoryPort stores,
            FoodRequestScopeValidator scope) {
        return new BizServiceCalendarController(service, stores, scope);
    }

    /** OFF is a nonbusiness skeleton mode; never expose a policy endpoint through it. */
    public static final class PolicyEnabled implements Condition {
        @Override public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return !"off".equalsIgnoreCase(context.getEnvironment().getProperty("shop.food.migration.mode", "validate"));
        }
    }

    @Bean
    FoodRequestScopeValidator foodRequestScopeValidator() {
        return new FoodRequestScopeValidator();
    }
}

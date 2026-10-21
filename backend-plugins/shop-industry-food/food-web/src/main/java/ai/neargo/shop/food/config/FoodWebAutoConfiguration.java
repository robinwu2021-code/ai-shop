package ai.neargo.shop.food.config;

import ai.neargo.shop.food.api.FoodBizDataScopeInterceptor;
import ai.neargo.shop.food.api.FoodRequestScopeValidator;
import ai.neargo.shop.food.api.biz.BizCheckActionController;
import ai.neargo.shop.food.api.biz.BizCheckController;
import ai.neargo.shop.food.api.biz.BizCheckSeatController;
import ai.neargo.shop.food.api.biz.BizReservationController;
import ai.neargo.shop.food.api.biz.BizServiceCalendarController;
import ai.neargo.shop.food.api.biz.BizStoreOperationPolicyController;
import ai.neargo.shop.food.api.biz.BizTableAreaController;
import ai.neargo.shop.food.api.biz.BizTableController;
import ai.neargo.shop.food.api.biz.BizTableOperationController;
import ai.neargo.shop.food.api.biz.BizTableSharingController;
import ai.neargo.shop.food.api.biz.BizTableTokenController;
import ai.neargo.shop.food.api.mp.MpDiningSessionController;
import ai.neargo.shop.food.foodclient.FoodClientConfiguration;
import ai.neargo.shop.food.service.CheckService;
import ai.neargo.shop.food.service.DishService;
import ai.neargo.shop.food.service.OptionGroupService;
import ai.neargo.shop.food.service.ModifierService;
import ai.neargo.shop.food.service.BundleService;
import ai.neargo.shop.food.api.biz.BizBundleController;
import ai.neargo.shop.food.service.ConfigRevisionService;
import ai.neargo.shop.food.api.biz.BizConfigRevisionController;
import ai.neargo.shop.food.api.biz.BizModifierController;
import ai.neargo.shop.food.api.biz.BizOptionGroupController;
import ai.neargo.shop.food.api.biz.BizDishController;
import ai.neargo.shop.food.service.DiningSessionService;
import ai.neargo.shop.food.service.ReservationService;
import ai.neargo.shop.food.service.StoreOperationService;
import ai.neargo.shop.food.service.TableService;
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
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 餐饮模块装配入口。 */
@AutoConfiguration(afterName = "ai.neargo.shop.food.config.FoodAutoConfiguration")
@Import(FoodClientConfiguration.class)
@ConditionalOnFoodWebEnabled
@Profile({"api", "ops"})
public class FoodWebAutoConfiguration {
    @Bean
    @Profile("api")
    @Conditional(PolicyEnabled.class)
    @ConditionalOnBean({ConfigRevisionService.class, StoreDirectoryPort.class})
    @ConditionalOnMissingBean(BizConfigRevisionController.class)
    BizConfigRevisionController configRevisionController(ConfigRevisionService service, StoreDirectoryPort stores,
            FoodRequestScopeValidator scope) {
        return new BizConfigRevisionController(service, stores, scope);
    }

    @Bean
    @Profile("api")
    @Conditional(PolicyEnabled.class)
    @ConditionalOnBean({BundleService.class, StoreDirectoryPort.class})
    @ConditionalOnMissingBean(BizBundleController.class)
    BizBundleController bundleController(BundleService service, StoreDirectoryPort stores,
            FoodRequestScopeValidator scope) {
        return new BizBundleController(service, stores, scope);
    }

    @Bean
    @Profile("api")
    @Conditional(PolicyEnabled.class)
    @ConditionalOnBean({ModifierService.class, StoreDirectoryPort.class})
    @ConditionalOnMissingBean(BizModifierController.class)
    BizModifierController modifierController(ModifierService service, StoreDirectoryPort stores,
            FoodRequestScopeValidator scope) {
        return new BizModifierController(service, stores, scope);
    }

    @Bean
    @Profile("api")
    @Conditional(PolicyEnabled.class)
    @ConditionalOnBean({OptionGroupService.class, StoreDirectoryPort.class})
    @ConditionalOnMissingBean(BizOptionGroupController.class)
    BizOptionGroupController optionGroupController(OptionGroupService service, StoreDirectoryPort stores,
            FoodRequestScopeValidator scope) {
        return new BizOptionGroupController(service, stores, scope);
    }

    @Bean
    @Profile("api")
    @Conditional(PolicyEnabled.class)
    @ConditionalOnBean({DishService.class, StoreDirectoryPort.class})
    @ConditionalOnMissingBean(BizDishController.class)
    BizDishController dishController(DishService service, StoreDirectoryPort stores, FoodRequestScopeValidator scope) {
        return new BizDishController(service, stores, scope);
    }

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

    @Bean
    @Profile("api")
    @Conditional(PolicyEnabled.class)
    @ConditionalOnBean({TableService.class, StoreDirectoryPort.class})
    @ConditionalOnMissingBean(BizTableAreaController.class)
    BizTableAreaController tableAreaController(TableService service, StoreDirectoryPort stores,
            FoodRequestScopeValidator scope) {
        return new BizTableAreaController(service, stores, scope);
    }

    @Bean
    @Profile("api")
    @Conditional(PolicyEnabled.class)
    @ConditionalOnBean({TableService.class, StoreDirectoryPort.class})
    @ConditionalOnMissingBean(BizTableController.class)
    BizTableController tableController(TableService service, StoreDirectoryPort stores,
            FoodRequestScopeValidator scope) {
        return new BizTableController(service, stores, scope);
    }

    @Bean
    @Profile("api")
    @Conditional(PolicyEnabled.class)
    @ConditionalOnBean({DiningSessionService.class, StoreDirectoryPort.class})
    @ConditionalOnMissingBean(BizTableTokenController.class)
    BizTableTokenController tableTokenController(
            DiningSessionService service, StoreDirectoryPort stores,
            FoodRequestScopeValidator scope) {
        return new BizTableTokenController(service,stores,scope);
    }

    @Bean
    @Profile("api")
    @Conditional(PolicyEnabled.class)
    @ConditionalOnBean({DiningSessionService.class, StoreDirectoryPort.class})
    @ConditionalOnMissingBean(MpDiningSessionController.class)
    MpDiningSessionController diningSessionController(
            DiningSessionService service, StoreDirectoryPort stores) {
        return new MpDiningSessionController(service,stores);
    }

    @Bean
    @Profile("api")
    @Conditional(PolicyEnabled.class)
    @ConditionalOnBean({CheckService.class, StoreDirectoryPort.class})
    @ConditionalOnMissingBean(BizCheckController.class)
    BizCheckController bizCheckController(
            CheckService service,StoreDirectoryPort stores,FoodRequestScopeValidator scope) {
        return new BizCheckController(service,stores,scope);
    }

    @Bean
    @Profile("api")
    @Conditional(PolicyEnabled.class)
    @ConditionalOnBean({CheckService.class, StoreDirectoryPort.class})
    @ConditionalOnMissingBean(BizCheckSeatController.class)
    BizCheckSeatController bizCheckSeatController(
            CheckService service,StoreDirectoryPort stores,FoodRequestScopeValidator scope) {
        return new BizCheckSeatController(service,stores,scope);
    }

    @Bean
    @Profile("api")
    @Conditional(PolicyEnabled.class)
    @ConditionalOnBean({CheckService.class, StoreDirectoryPort.class})
    @ConditionalOnMissingBean(BizTableSharingController.class)
    BizTableSharingController bizTableSharingController(
            CheckService service,StoreDirectoryPort stores,FoodRequestScopeValidator scope) {
        return new BizTableSharingController(service,stores,scope);
    }

    @Bean
    @Profile("api")
    @Conditional(PolicyEnabled.class)
    @ConditionalOnBean({CheckService.class, StoreDirectoryPort.class})
    @ConditionalOnMissingBean(BizTableOperationController.class)
    BizTableOperationController bizTableOperationController(
            CheckService service,StoreDirectoryPort stores,FoodRequestScopeValidator scope) {
        return new BizTableOperationController(service,stores,scope);
    }

    @Bean
    @Profile("api")
    @Conditional(PolicyEnabled.class)
    @ConditionalOnBean({CheckService.class, StoreDirectoryPort.class})
    @ConditionalOnMissingBean(BizCheckActionController.class)
    BizCheckActionController bizCheckActionController(
            CheckService service,StoreDirectoryPort stores,FoodRequestScopeValidator scope) {
        return new BizCheckActionController(service,stores,scope);
    }

    @Bean
    @Profile("api")
    @Conditional(PolicyEnabled.class)
    @ConditionalOnBean({ReservationService.class, StoreDirectoryPort.class})
    @ConditionalOnMissingBean(BizReservationController.class)
    BizReservationController reservationController(
            ReservationService service,StoreDirectoryPort stores,FoodRequestScopeValidator scope) {
        return new BizReservationController(service,stores,scope);
    }

    @Bean
    @Profile("api")
    @Conditional(PolicyEnabled.class)
    WebMvcConfigurer foodBizDataScopeConfigurer() {
        return new WebMvcConfigurer() {
            @Override public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(new FoodBizDataScopeInterceptor()).addPathPatterns("/biz/x/food/**");
            }
        };
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

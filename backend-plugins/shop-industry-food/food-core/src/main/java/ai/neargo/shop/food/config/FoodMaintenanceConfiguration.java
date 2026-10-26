package ai.neargo.shop.food.config;

import ai.neargo.shop.food.service.BusinessReturnApprovalService;
import ai.neargo.shop.food.service.FoodMaintenanceService;
import ai.neargo.shop.food.service.ProductionConsumptionService;
import ai.neargo.shop.food.service.ScheduledDiningDispatchService;
import ai.neargo.shop.food.service.impl.FoodMaintenanceServiceImpl;
import ai.neargo.shop.food.service.impl.QueueNotificationServiceImpl;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;

/**
 * 后台补偿（预约出餐、叫号重发、耗用恢复、退菜改单恢复）的本地执行者。
 *
 * <p><b>不挂 {@code shop.job.enabled}</b>：独立部署时 food-server 不跑任务运行时，
 * 但它是这些补偿的原宿主，shop-app 的平台任务要经内部入口调到这里。触发端（JobHandler）
 * 在 food-web 的 {@code FoodMaintenanceJobs}，那里才看 {@code shop.job.enabled}。
 */
@AutoConfiguration(afterName = {"ai.neargo.shop.food.config.FoodAutoConfiguration",
        "ai.neargo.shop.food.config.FoodBusinessOperationsConfiguration",
        "ai.neargo.shop.food.config.FoodBusinessAmendmentConfiguration"})
@ConditionalOnFoodEnabled
@Conditional(FoodPersistenceConfiguration.PersistenceEnabled.class)
public class FoodMaintenanceConfiguration {
    @Bean
    @ConditionalOnMissingBean(FoodMaintenanceService.class)
    FoodMaintenanceService foodMaintenanceService(ObjectProvider<ScheduledDiningDispatchService> dispatch,
            ObjectProvider<QueueNotificationServiceImpl> notices,
            ObjectProvider<ProductionConsumptionService> production,
            ObjectProvider<BusinessReturnApprovalService> amendments) {
        return new FoodMaintenanceServiceImpl(dispatch::getIfAvailable, notices::getIfAvailable,
                production::getIfAvailable, amendments::getIfAvailable);
    }
}

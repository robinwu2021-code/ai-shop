package ai.neargo.shop.food.config;

import ai.neargo.shop.food.service.ProductionConsumptionService;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Conditional;
import org.springframework.scheduling.annotation.Scheduled;

/** Independent non-funds recovery wiring; all retries retain the original source event. */
@AutoConfiguration(after = FoodOperationsConfiguration.class)
@ConditionalOnProperty(prefix = "shop.job", name = "enabled", havingValue = "true")
@ConditionalOnFoodEnabled
@Conditional(FoodPersistenceConfiguration.PersistenceEnabled.class)
public class FoodProductionRecoveryConfiguration {
    @Bean
    ProductionRecoveryJob productionRecoveryJob(ProductionConsumptionService service) {
        return new ProductionRecoveryJob(service);
    }

    public static class ProductionRecoveryJob {
        private final ProductionConsumptionService service;

        ProductionRecoveryJob(ProductionConsumptionService service) {
            this.service = service;
        }

        @Scheduled(fixedDelayString = "${shop.food.production.recovery-delay-ms:15000}")
        public void recover() {
            service.recover(50);
        }
    }
}

package ai.neargo.shop.food.config;

import ai.neargo.shop.food.api.FoodRequestScopeValidator;
import ai.neargo.shop.food.api.biz.BizBusinessOperationsController;
import ai.neargo.shop.food.foodclient.FoodBusinessOperationsClientConfiguration;
import ai.neargo.shop.food.service.BusinessOperationsService;
import ai.neargo.shop.food.service.CheckCollaborationService;
import ai.neargo.shop.food.service.FoodFeeSourceService;
import ai.neargo.shop.food.service.QueueNotificationService;
import ai.neargo.shop.food.service.ReservationPreorderService;
import ai.neargo.shop.food.service.ServiceFormService;
import ai.neargo.shop.food.service.ServiceSlotService;
import ai.neargo.shop.spi.user.StoreDirectoryPort;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@ConditionalOnFoodWebEnabled
@Conditional(FoodWebAutoConfiguration.PolicyEnabled.class)
@Import(FoodBusinessOperationsClientConfiguration.class)
public class FoodBusinessOperationsWebConfiguration {
    @Bean
    @Profile("api")
    @ConditionalOnMissingBean(BizBusinessOperationsController.class)
    @ConditionalOnBean({
        CheckCollaborationService.class,
        ReservationPreorderService.class,
        ServiceSlotService.class,
        BusinessOperationsService.class,
        QueueNotificationService.class,
        FoodFeeSourceService.class,
        ServiceFormService.class,
        StoreDirectoryPort.class
    })
    BizBusinessOperationsController businessOperationsController(
            CheckCollaborationService checks,
            ReservationPreorderService preorders,
            ServiceSlotService slots,
            BusinessOperationsService ops,
            QueueNotificationService notices,
            FoodFeeSourceService fees,
            ServiceFormService forms,
            StoreDirectoryPort stores,
            FoodRequestScopeValidator validator) {
        return new BizBusinessOperationsController(
                forms, fees, notices, checks, preorders, slots, ops, stores, validator);
    }
}

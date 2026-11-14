package ai.neargo.shop.food.foodclient;

import ai.neargo.shop.food.config.ConditionalOnFoodWebEnabled;
import ai.neargo.shop.food.config.FoodWebAutoConfiguration;
import ai.neargo.shop.food.service.BusinessOperationsService;
import ai.neargo.shop.food.service.CheckCollaborationService;
import ai.neargo.shop.food.service.FoodFeeSourceService;
import ai.neargo.shop.food.service.QueueNotificationService;
import ai.neargo.shop.food.service.ReservationPreorderService;
import ai.neargo.shop.food.service.ServiceFormService;
import ai.neargo.shop.food.service.ServiceSlotService;
import ai.neargo.shop.food.spi.BusinessOperationsInternalApi;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.ServiceName;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnFoodWebEnabled
@ConditionalOnProperty(prefix = "shop.food", name = "deployment", havingValue = "standalone")
@ConditionalOnBean(InternalHttp.class)
@Conditional(FoodWebAutoConfiguration.PolicyEnabled.class)
@Profile({"api", "ops"})
public class FoodBusinessOperationsClientConfiguration {
    @Bean
    @ConditionalOnMissingBean(ServiceFormService.class)
    ServiceFormService remoteServiceFormService(InternalHttp http) {
        return new RemoteServiceFormService(
                http.client(
                        ServiceName.FOOD,
                        BusinessOperationsInternalApi.class,
                        Duration.ofSeconds(10)));
    }

    @Bean
    @ConditionalOnMissingBean(FoodFeeSourceService.class)
    FoodFeeSourceService remoteFoodFeeSourceService(InternalHttp http) {
        return new RemoteFoodFeeSourceService(
                http.client(
                        ServiceName.FOOD,
                        BusinessOperationsInternalApi.class,
                        Duration.ofSeconds(10)));
    }

    @Bean
    @ConditionalOnMissingBean(QueueNotificationService.class)
    QueueNotificationService remoteQueueNotificationService(InternalHttp http) {
        return new RemoteQueueNotificationService(
                http.client(
                        ServiceName.FOOD,
                        BusinessOperationsInternalApi.class,
                        Duration.ofSeconds(10)));
    }

    @Bean
    @ConditionalOnMissingBean(CheckCollaborationService.class)
    CheckCollaborationService remoteCheckCollaborationService(InternalHttp http) {
        return new RemoteCheckCollaborationService(
                http.client(
                        ServiceName.FOOD,
                        BusinessOperationsInternalApi.class,
                        Duration.ofSeconds(10)));
    }

    @Bean
    @ConditionalOnMissingBean(ReservationPreorderService.class)
    ReservationPreorderService remoteReservationPreorderService(InternalHttp http) {
        return new RemoteReservationPreorderService(
                http.client(
                        ServiceName.FOOD,
                        BusinessOperationsInternalApi.class,
                        Duration.ofSeconds(10)));
    }

    @Bean
    @ConditionalOnMissingBean(ServiceSlotService.class)
    ServiceSlotService remoteServiceSlotService(InternalHttp http) {
        return new RemoteServiceSlotService(
                http.client(
                        ServiceName.FOOD,
                        BusinessOperationsInternalApi.class,
                        Duration.ofSeconds(10)));
    }

    @Bean
    @ConditionalOnMissingBean(BusinessOperationsService.class)
    BusinessOperationsService remoteBusinessOperationsService(InternalHttp http) {
        return new RemoteBusinessOperationsService(
                http.client(
                        ServiceName.FOOD,
                        BusinessOperationsInternalApi.class,
                        Duration.ofSeconds(10)));
    }
}

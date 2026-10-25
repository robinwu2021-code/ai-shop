package ai.neargo.shop.food.config;

import ai.neargo.shop.food.api.FoodRequestScopeValidator;
import ai.neargo.shop.food.api.biz.BizFoodKdsController;
import ai.neargo.shop.food.api.biz.BizFoodStationRuntimeController;
import ai.neargo.shop.food.event.DiningKdsEventConsumer;
import ai.neargo.shop.food.foodclient.RemoteFoodKdsService;
import ai.neargo.shop.food.foodclient.RemoteFoodStationRuntimeService;
import ai.neargo.shop.food.service.FoodKdsService;
import ai.neargo.shop.food.service.FoodKdsStreamService;
import ai.neargo.shop.food.service.FoodPrintService;
import ai.neargo.shop.food.service.FoodStationRuntimeService;
import ai.neargo.shop.food.service.impl.FoodKdsStreamServiceImpl;
import ai.neargo.shop.food.spi.FoodKdsInternalApi;
import ai.neargo.shop.food.spi.FoodStationRuntimeInternalApi;
import ai.neargo.shop.spi.printing.PrintPort;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.ServiceName;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Profile;

import tools.jackson.databind.ObjectMapper;

import java.time.Duration;

/** Keeps long-lived SSE at the gateway while standalone FOOD serves short typed replay calls. */
@AutoConfiguration(
        afterName = {
            "ai.neargo.shop.food.config.FoodKdsAutoConfiguration",
            "ai.neargo.shop.food.config.FoodWebAutoConfiguration"
        })
@ConditionalOnFoodWebEnabled
@Profile("api")
@Conditional(FoodWebAutoConfiguration.PolicyEnabled.class)
public class FoodKdsWebAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(FoodStationRuntimeService.class)
    @ConditionalOnProperty(prefix = "shop.food", name = "deployment", havingValue = "standalone")
    FoodStationRuntimeService remoteFoodStationRuntimeService(InternalHttp http) {
        return new RemoteFoodStationRuntimeService(
                http.client(
                        ServiceName.FOOD,
                        FoodStationRuntimeInternalApi.class,
                        Duration.ofSeconds(5)));
    }

    @Bean
    @ConditionalOnMissingBean(BizFoodStationRuntimeController.class)
    BizFoodStationRuntimeController bizFoodStationRuntimeController(
            FoodStationRuntimeService service,
            StoreDirectoryPort stores,
            FoodRequestScopeValidator validator) {
        return new BizFoodStationRuntimeController(service, stores, validator);
    }

    @Bean
    @ConditionalOnMissingBean(FoodKdsService.class)
    @ConditionalOnProperty(prefix = "shop.food", name = "deployment", havingValue = "standalone")
    FoodKdsService remoteFoodKdsService(InternalHttp http) {
        return new RemoteFoodKdsService(
                http.client(ServiceName.FOOD, FoodKdsInternalApi.class, Duration.ofSeconds(5)));
    }

    @Bean
    @ConditionalOnMissingBean(FoodKdsStreamService.class)
    FoodKdsStreamService foodKdsStreamService(FoodKdsService service) {
        return new FoodKdsStreamServiceImpl(service);
    }

    @Bean
    @ConditionalOnMissingBean(BizFoodKdsController.class)
    BizFoodKdsController bizFoodKdsController(
            FoodKdsService service,
            FoodKdsStreamService streams,
            StoreDirectoryPort stores,
            FoodRequestScopeValidator validator) {
        return new BizFoodKdsController(service, streams, stores, validator);
    }

    @Bean
    @ConditionalOnMissingBean(DiningKdsEventConsumer.class)
    DiningKdsEventConsumer diningKdsEventConsumer(
            FoodKdsService service,
            ObjectMapper json,
            ObjectProvider<PrintPort> printing,
            ObjectProvider<FoodPrintService> documents) {
        return new DiningKdsEventConsumer(
                service, json, printing::getIfAvailable, documents::getIfAvailable);
    }
}

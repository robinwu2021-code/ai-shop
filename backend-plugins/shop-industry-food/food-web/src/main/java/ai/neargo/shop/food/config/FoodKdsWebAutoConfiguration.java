package ai.neargo.shop.food.config;

import ai.neargo.shop.food.spi.FoodDeviceRefresh;
import ai.neargo.shop.food.spi.InProcessFoodDeviceRefresh;

import ai.neargo.shop.food.api.FoodRequestScopeValidator;
import ai.neargo.shop.food.api.biz.BizFoodKdsController;
import ai.neargo.shop.food.api.biz.BizFoodStationRuntimeController;
import ai.neargo.shop.food.api.stream.FoodKdsStreamTransport;
import ai.neargo.shop.food.api.stream.FoodKdsStreamTransportImpl;
import ai.neargo.shop.food.event.DiningKdsEventConsumer;
import ai.neargo.shop.food.foodclient.RemoteFoodKdsService;
import ai.neargo.shop.food.foodclient.RemoteFoodStationRuntimeService;
import ai.neargo.shop.food.service.FoodKdsService;
import ai.neargo.shop.food.service.FoodStationRuntimeService;
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

    /**
     * 本进程里没有餐饮核心（独立部署的网关侧）时的刷新信号：只在本进程内传递，
     * 收得到本进程消费者发的厨房事件刷新；核心那边的开停、叫号刷新靠流的定时兜底（TDD §2.8 D-3）。
     */
    @Bean
    @ConditionalOnMissingBean(FoodDeviceRefresh.class)
    FoodDeviceRefresh inProcessFoodDeviceRefresh() {
        return new InProcessFoodDeviceRefresh();
    }

    @Bean
    @ConditionalOnMissingBean(FoodKdsStreamTransport.class)
    FoodKdsStreamTransport foodKdsStreamService(FoodKdsService service, FoodDeviceRefresh refresh) {
        return new FoodKdsStreamTransportImpl(service, refresh);
    }

    @Bean
    @ConditionalOnMissingBean(BizFoodKdsController.class)
    BizFoodKdsController bizFoodKdsController(
            FoodKdsService service,
            FoodKdsStreamTransport streams,
            StoreDirectoryPort stores,
            FoodRequestScopeValidator validator) {
        return new BizFoodKdsController(service, streams, stores, validator);
    }

    @Bean
    @ConditionalOnMissingBean(DiningKdsEventConsumer.class)
    DiningKdsEventConsumer diningKdsEventConsumer(
            FoodDeviceRefresh refresh, ObjectMapper json, ObjectProvider<PrintPort> printing) {
        return new DiningKdsEventConsumer(refresh, json, printing::getIfAvailable);
    }
}

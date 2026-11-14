package ai.neargo.shop.food.foodclient;

import ai.neargo.shop.food.config.ConditionalOnFoodWebEnabled;
import ai.neargo.shop.food.spi.FoodStockManagementInternalApi;
import ai.neargo.shop.food.spi.FoodStockSyncInternalApi;
import tools.jackson.databind.ObjectMapper;
import ai.neargo.shop.spi.food.FoodLocalStockInternalApi;
import ai.neargo.shop.spi.food.FoodLocalStockPort;
import ai.neargo.shop.food.service.*;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.ServiceName;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** No local persistence bean is introduced into the standalone gateway. Includes worker recovery calls. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnFoodWebEnabled
@ConditionalOnBean(InternalHttp.class)
@ConditionalOnProperty(name={"shop.food.local-stock.enabled"},havingValue="true")
public class FoodStockClientConfiguration {
    @Configuration(proxyBeanMethods=false)
    @ConditionalOnProperty(name="shop.food.deployment",havingValue="standalone")
    static class Standalone {
        @Bean
        RemoteFoodStockSyncDispatcher remoteFoodStockSyncDispatcher(InternalHttp http,ObjectMapper json) {
            return new RemoteFoodStockSyncDispatcher(http.client(ServiceName.FOOD,
                    FoodStockSyncInternalApi.class,Duration.ofSeconds(30)),json);
        }
        @Bean @ConditionalOnMissingBean(FoodReturnStockService.class)
        FoodReturnStockService remoteFoodReturnStockService(InternalHttp http) {
            return new RemoteFoodReturnStockService(http.client(ServiceName.FOOD,ai.neargo.shop.food.spi.FoodReturnStockInternalApi.class,Duration.ofSeconds(5)));
        }
        @Bean @ConditionalOnMissingBean(FoodLocalStockPort.class)
        FoodLocalStockPort remoteFoodLocalStockPort(InternalHttp http) {
            return new RemoteFoodLocalStockPort(http.client(ServiceName.FOOD,FoodLocalStockInternalApi.class,Duration.ofSeconds(5)));
        }
        @Bean @ConditionalOnMissingBean({FoodStockItemService.class,FoodStockBalanceService.class})
        RemoteFoodStockManagementService remoteFoodStockManagementService(InternalHttp http) {
            return new RemoteFoodStockManagementService(http.client(ServiceName.FOOD,FoodStockManagementInternalApi.class,Duration.ofSeconds(5)));
        }
        @Bean @ConditionalOnMissingBean(FoodStockSyncService.class)
        FoodStockSyncService remoteFoodStockSyncService(InternalHttp http) {
            return new RemoteFoodStockSyncService(http.client(ServiceName.FOOD,FoodStockManagementInternalApi.class,Duration.ofSeconds(5)));
        }
        @Bean @ConditionalOnMissingBean(FoodStockReconciliationService.class)
        FoodStockReconciliationService remoteFoodStockReconciliationService(InternalHttp http) {
            return new RemoteFoodStockReconciliationService(http.client(ServiceName.FOOD,FoodStockManagementInternalApi.class,Duration.ofSeconds(5)));
        }
    }
}

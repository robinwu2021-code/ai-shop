package ai.neargo.shop.food.foodclient;

import ai.neargo.shop.food.config.ConditionalOnFoodWebEnabled;
import ai.neargo.shop.food.config.FoodWebAutoConfiguration.PolicyEnabled;
import ai.neargo.shop.food.service.StoreOperationService;
import ai.neargo.shop.food.service.TableService;
import ai.neargo.shop.food.spi.ServiceCalendarInternalApi;
import ai.neargo.shop.food.spi.StoreOperationInternalApi;
import ai.neargo.shop.food.spi.StorePolicyPublicationInternalApi;
import ai.neargo.shop.food.spi.TableInternalApi;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.ServiceName;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Only the standalone gateway uses a remote Service; embedded assembly retains the local implementation. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnFoodWebEnabled
@ConditionalOnProperty(prefix = "shop.food", name = "deployment", havingValue = "standalone")
@ConditionalOnBean(InternalHttp.class)
@Conditional(PolicyEnabled.class)
@Profile({"api", "ops"})
public class FoodClientConfiguration {
    @Bean
    @ConditionalOnMissingBean(TableService.class)
    TableService remoteTableService(InternalHttp http) {
        return new RemoteTableService(http.client(ServiceName.FOOD, TableInternalApi.class, Duration.ofSeconds(5)));
    }

    @Bean
    @ConditionalOnMissingBean(StoreOperationService.class)
    StoreOperationService remoteStoreOperationService(InternalHttp http) {
        return new RemoteStoreOperationService(http.client(ServiceName.FOOD, StoreOperationInternalApi.class,
                Duration.ofSeconds(5)),
                http.client(ServiceName.FOOD, StorePolicyPublicationInternalApi.class, Duration.ofSeconds(5)),
                http.client(ServiceName.FOOD, ServiceCalendarInternalApi.class, Duration.ofSeconds(5)));
    }
}

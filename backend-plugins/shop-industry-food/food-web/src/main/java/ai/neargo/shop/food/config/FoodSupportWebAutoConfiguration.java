package ai.neargo.shop.food.config;

import ai.neargo.shop.food.api.ops.OpsFoodSupportController;
import ai.neargo.shop.food.foodclient.RemoteFoodSupportService;
import ai.neargo.shop.food.service.FoodSupportService;
import ai.neargo.shop.food.spi.FoodSupportInternalApi;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.ServiceName;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;

import java.time.Duration;

/** An ops host reads the same contract with either local or remote FOOD deployment. */
@AutoConfiguration(afterName = "ai.neargo.shop.food.config.FoodSupportAutoConfiguration")
@Profile("ops")
@ConditionalOnFoodWebEnabled
public class FoodSupportWebAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(FoodSupportService.class)
    @ConditionalOnProperty(prefix = "shop.food", name = "deployment", havingValue = "standalone")
    FoodSupportService remoteFoodSupportService(InternalHttp http) {
        return new RemoteFoodSupportService(
                http.client(ServiceName.FOOD, FoodSupportInternalApi.class, Duration.ofSeconds(5)));
    }

    @Bean
    @ConditionalOnMissingBean(OpsFoodSupportController.class)
    OpsFoodSupportController opsFoodSupportController(
            FoodSupportService service, StoreDirectoryPort stores) {
        return new OpsFoodSupportController(service, stores);
    }
}

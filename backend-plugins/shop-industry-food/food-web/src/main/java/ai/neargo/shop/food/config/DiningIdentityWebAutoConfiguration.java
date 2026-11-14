package ai.neargo.shop.food.config;

import ai.neargo.shop.food.api.FoodRequestScopeValidator;
import ai.neargo.shop.food.api.biz.BizDiningIdentityController;
import ai.neargo.shop.food.api.mp.MpDiningDeviceSessionController;
import ai.neargo.shop.food.foodclient.RemoteDiningIdentityService;
import ai.neargo.shop.food.service.DiningIdentityService;
import ai.neargo.shop.food.spi.DiningIdentityInternalApi;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.ServiceName;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Profile;

import java.time.Duration;

/** Routes trusted business scopes to one identity provider without creating platform accounts. */
@AutoConfiguration(
        afterName = {
            "ai.neargo.shop.food.config.DiningIdentityAutoConfiguration",
            "ai.neargo.shop.food.config.FoodWebAutoConfiguration"
        })
@Profile("api")
@ConditionalOnFoodWebEnabled
@Conditional(FoodWebAutoConfiguration.PolicyEnabled.class)
public class DiningIdentityWebAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(DiningIdentityService.class)
    @ConditionalOnProperty(prefix = "shop.food", name = "deployment", havingValue = "standalone")
    DiningIdentityService remoteDiningIdentityService(InternalHttp http) {
        return new RemoteDiningIdentityService(
                http.client(
                        ServiceName.FOOD, DiningIdentityInternalApi.class, Duration.ofSeconds(5)));
    }

    @Bean
    @ConditionalOnMissingBean(BizDiningIdentityController.class)
    BizDiningIdentityController bizDiningIdentityController(
            DiningIdentityService service,
            StoreDirectoryPort stores,
            FoodRequestScopeValidator validator) {
        return new BizDiningIdentityController(service, stores, validator);
    }

    @Bean
    @ConditionalOnMissingBean(MpDiningDeviceSessionController.class)
    MpDiningDeviceSessionController mpDiningDeviceSessionController(
            DiningIdentityService service, StoreDirectoryPort stores) {
        return new MpDiningDeviceSessionController(service, stores);
    }
}

package ai.neargo.shop.food.config;

import ai.neargo.shop.food.api.FoodRequestScopeValidator;
import ai.neargo.shop.food.api.biz.BizDiningDeliveryController;
import ai.neargo.shop.food.api.mp.MpDiningDeliveryController;
import ai.neargo.shop.food.foodclient.RemoteDiningDeliveryService;
import ai.neargo.shop.food.service.DiningDeliveryService;
import ai.neargo.shop.food.spi.DiningDeliveryInternalApi;
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

/** Preserves original cart authorization across embedded and typed standalone address providers. */
@AutoConfiguration(
        afterName = {
            "ai.neargo.shop.food.config.DiningDeliveryAutoConfiguration",
            "ai.neargo.shop.food.config.FoodWebAutoConfiguration"
        })
@Profile("api")
@ConditionalOnFoodWebEnabled
@Conditional(FoodWebAutoConfiguration.PolicyEnabled.class)
public class DiningDeliveryWebAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(DiningDeliveryService.class)
    @ConditionalOnProperty(prefix = "shop.food", name = "deployment", havingValue = "standalone")
    DiningDeliveryService remoteDiningDeliveryService(InternalHttp http) {
        return new RemoteDiningDeliveryService(
                http.client(
                        ServiceName.FOOD, DiningDeliveryInternalApi.class, Duration.ofSeconds(5)));
    }

    @Bean
    @ConditionalOnMissingBean(BizDiningDeliveryController.class)
    BizDiningDeliveryController bizDiningDeliveryController(
            DiningDeliveryService service,
            StoreDirectoryPort stores,
            FoodRequestScopeValidator validator) {
        return new BizDiningDeliveryController(service, stores, validator);
    }

    @Bean
    @ConditionalOnMissingBean(MpDiningDeliveryController.class)
    MpDiningDeliveryController mpDiningDeliveryController(DiningDeliveryService service) {
        return new MpDiningDeliveryController(service);
    }
}

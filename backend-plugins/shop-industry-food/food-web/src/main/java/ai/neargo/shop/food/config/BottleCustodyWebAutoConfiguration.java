package ai.neargo.shop.food.config;

import ai.neargo.shop.food.api.FoodRequestScopeValidator;
import ai.neargo.shop.food.api.biz.BizBottleCustodyController;
import ai.neargo.shop.food.foodclient.RemoteBottleCustodyService;
import ai.neargo.shop.food.service.BottleCustodyService;
import ai.neargo.shop.food.spi.BottleCustodyInternalApi;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.ServiceName;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;

import java.time.Duration;

/**
 * The host exposes identical B APIs using either the native FOOD owner or its typed service
 * transport.
 */
@AutoConfiguration(
        afterName = {
            "ai.neargo.shop.food.config.BottleCustodyAutoConfiguration",
            "ai.neargo.shop.food.config.FoodWebAutoConfiguration"
        })
@Profile("api")
@ConditionalOnFoodWebEnabled
@ConditionalOnProperty(name = "shop.food.custody.enabled", havingValue = "true")
public class BottleCustodyWebAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(BottleCustodyService.class)
    @ConditionalOnProperty(name = "shop.food.deployment", havingValue = "standalone")
    BottleCustodyService remoteBottleCustodyService(InternalHttp http) {
        return new RemoteBottleCustodyService(
                http.client(
                        ServiceName.FOOD, BottleCustodyInternalApi.class, Duration.ofSeconds(5)));
    }

    @Bean
    @ConditionalOnMissingBean(BizBottleCustodyController.class)
    BizBottleCustodyController bizBottleCustodyController(
            BottleCustodyService service,
            StoreDirectoryPort stores,
            FoodRequestScopeValidator validator) {
        return new BizBottleCustodyController(service, stores, validator);
    }
}

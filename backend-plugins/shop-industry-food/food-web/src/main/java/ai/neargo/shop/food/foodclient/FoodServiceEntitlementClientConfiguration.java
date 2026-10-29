package ai.neargo.shop.food.foodclient;

import ai.neargo.shop.food.config.ConditionalOnFoodWebEnabled;
import ai.neargo.shop.food.service.ServiceEntitlementService;
import ai.neargo.shop.food.spi.ServiceEntitlementInternalApi;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.ServiceName;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.time.Duration;

/**
 * The original FOOD scope signing interceptor and service-token transport remain attached by the
 * existing client runtime.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnFoodWebEnabled
@ConditionalOnProperty(name = "shop.food.deployment", havingValue = "standalone")
@Profile({"api", "ops"})
public class FoodServiceEntitlementClientConfiguration {
    @Bean
    @ConditionalOnProperty(name = "shop.food.service-entitlements.enabled", havingValue = "true")
    @ConditionalOnBean(InternalHttp.class)
    @ConditionalOnMissingBean(ServiceEntitlementService.class)
    ServiceEntitlementService remoteServiceEntitlementService(InternalHttp http) {
        return new RemoteServiceEntitlementService(
                http.client(
                        ServiceName.FOOD,
                        ServiceEntitlementInternalApi.class,
                        Duration.ofSeconds(10)));
    }
}

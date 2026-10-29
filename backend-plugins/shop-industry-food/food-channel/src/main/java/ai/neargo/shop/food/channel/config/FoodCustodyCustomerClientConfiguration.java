package ai.neargo.shop.food.channel.config;

import ai.neargo.shop.spi.user.CustomerAccountInternalApi;
import ai.neargo.shop.spi.user.CustomerAccountQueryPort;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCalls;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import java.time.Duration;

/**
 * Standalone FOOD reads minimal genuine account existence; a missing result is never an anonymous
 * customer.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "shop.food.custody.enabled", havingValue = "true")
public class FoodCustodyCustomerClientConfiguration {
    @Bean
    @Lazy
    CustomerAccountInternalApi customerAccountInternalApi(InternalHttp http) {
        return http.client(
                ServiceName.PLATFORM, CustomerAccountInternalApi.class, Duration.ofSeconds(5));
    }

    @Bean
    @ConditionalOnMissingBean(CustomerAccountQueryPort.class)
    CustomerAccountQueryPort customerAccountQueryPort(
            ObjectProvider<CustomerAccountInternalApi> api) {
        return query -> {
            var result = ServiceCalls.call(ServiceName.PLATFORM, () -> api.getObject().find(query));
            if (result == null || !query.equals(result.query()))
                throw new IllegalStateException("Wrong customer scope");
            return result;
        };
    }
}

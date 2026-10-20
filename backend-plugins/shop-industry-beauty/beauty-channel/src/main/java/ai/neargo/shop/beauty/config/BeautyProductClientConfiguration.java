package ai.neargo.shop.beauty.config;

import ai.neargo.shop.beauty.port.RemoteServiceProductFactsPort;
import ai.neargo.shop.spi.product.ServiceProductFactsPort;
import ai.neargo.shop.spi.product.ServiceProductFactsInternalApi;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.ServiceName;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Imported by the independent server only; embedded uses the product-owned local implementation. */
@Configuration(proxyBeanMethods = false)
public class BeautyProductClientConfiguration {
    @Bean
    @ConditionalOnMissingBean(ServiceProductFactsPort.class)
    ServiceProductFactsPort remoteServiceProductFactsPort(InternalHttp http) {
        return new RemoteServiceProductFactsPort(http.client(ServiceName.PLATFORM, ServiceProductFactsInternalApi.class,
                Duration.ofSeconds(5)));
    }
}

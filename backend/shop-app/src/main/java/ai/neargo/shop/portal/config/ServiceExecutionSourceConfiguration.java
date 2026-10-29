package ai.neargo.shop.portal.config;

import ai.neargo.shop.portal.port.RemoteServiceExecutionSourcePort;
import ai.neargo.shop.spi.trade.ServiceExecutionSourcePort;
import ai.neargo.shop.spi.trade.ServiceExecutionSourceInternalApi;
import ai.neargo.shop.svc.InternalBindings;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.ServiceName;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Duration;

/** Default retail has no reverse dependency; only enabled standalone beauty resolves the original source remotely. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "shop.beauty", name = "enabled", havingValue = "true")
public class ServiceExecutionSourceConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "shop.beauty", name = "deployment", havingValue = "standalone")
    @ConditionalOnMissingBean(ServiceExecutionSourcePort.class)
    ServiceExecutionSourcePort platformRemoteBeautyExecutionSource(InternalHttp http,
            @Value("${shop.services.internal-token:}") String token) {
        return new RemoteServiceExecutionSourcePort(http.client(ServiceName.BEAUTY,
                ServiceExecutionSourceInternalApi.class, Duration.ofSeconds(5)), new InternalBindings(token));
    }
}

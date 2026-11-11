package ai.neargo.shop.beauty.config;

import ai.neargo.shop.beauty.client.RemoteWorkOrderEntitlementUses;
import ai.neargo.shop.spi.entitlement.ServiceWorkOrderUseFactsInternalApi;
import ai.neargo.shop.spi.entitlement.ServiceWorkOrderUseFactsPort;
import ai.neargo.shop.spi.entitlement.ServiceWorkOrderUseInternalApi;
import ai.neargo.shop.spi.entitlement.ServiceWorkOrderUsePort;
import ai.neargo.shop.svc.InternalBindings;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCalls;
import java.time.Duration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(after = BeautyChannelAutoConfiguration.class,
        beforeName = "ai.neargo.shop.beauty.config.BeautyBusinessAutoConfiguration")
@ConditionalOnBean(InternalHttp.class)
@ConditionalOnProperty(prefix = "shop.beauty", name = "enabled", havingValue = "true")
public class BeautyEntitlementUseClientConfiguration {
    @Bean
    @ConditionalOnMissingClass("ai.neargo.shop.beauty.config.BeautyAutoConfiguration")
    @ConditionalOnProperty(prefix = "shop.beauty", name = "deployment", havingValue = "standalone")
    @ConditionalOnMissingBean(ServiceWorkOrderUsePort.class)
    ServiceWorkOrderUsePort remoteWorkOrderEntitlementUses(InternalHttp http, InternalBindings bindings) {
        return new RemoteWorkOrderEntitlementUses(http.client(ServiceName.BEAUTY,
                ServiceWorkOrderUseInternalApi.class, Duration.ofSeconds(5)), bindings);
    }

    @Bean
    @ConditionalOnMissingClass("ai.neargo.shop.paybridge.impl.ServiceWorkOrderUseFactsService")
    @ConditionalOnMissingBean(ServiceWorkOrderUseFactsPort.class)
    ServiceWorkOrderUseFactsPort remoteWorkOrderEntitlementFacts(InternalHttp http) {
        var api = http.client(ServiceName.PLATFORM, ServiceWorkOrderUseFactsInternalApi.class, Duration.ofSeconds(5));
        return lookup -> ServiceCalls.call(ServiceName.PLATFORM, () -> api.inspect(lookup));
    }
}

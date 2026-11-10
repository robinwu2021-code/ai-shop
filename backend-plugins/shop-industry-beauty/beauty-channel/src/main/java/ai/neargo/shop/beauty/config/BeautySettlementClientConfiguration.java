package ai.neargo.shop.beauty.config;

import ai.neargo.shop.beauty.client.RemoteWorkOrderSettlementService;
import ai.neargo.shop.beauty.service.WorkOrderSettlementService;
import ai.neargo.shop.beauty.spi.BeautySettlementInternalApi;
import ai.neargo.shop.svc.InternalBindings;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.ServiceName;
import java.time.Duration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(after = BeautyChannelAutoConfiguration.class)
@ConditionalOnBean(InternalHttp.class)
@ConditionalOnMissingClass("ai.neargo.shop.beauty.config.BeautyAutoConfiguration")
@ConditionalOnProperty(prefix = "shop.beauty", name = "enabled", havingValue = "true")
public class BeautySettlementClientConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "shop.beauty", name = "deployment", havingValue = "standalone")
    @ConditionalOnMissingBean(WorkOrderSettlementService.class)
    WorkOrderSettlementService remoteWorkOrderSettlementService(InternalHttp http, InternalBindings bindings) {
        return new RemoteWorkOrderSettlementService(http.client(ServiceName.BEAUTY,
                BeautySettlementInternalApi.class, Duration.ofSeconds(5)), bindings);
    }
}

package ai.neargo.shop.beauty.config;

import ai.neargo.shop.beauty.port.RemotePersonnelFactsPort;
import ai.neargo.shop.beauty.port.RemoteResourceFactsPort;
import ai.neargo.shop.beauty.port.RemoteScheduleWindowsPort;
import ai.neargo.shop.spi.personnel.PersonnelFactsPort;
import ai.neargo.shop.spi.resource.MerchantFactsInternalApi;
import ai.neargo.shop.spi.resource.ResourceFactsPort;
import ai.neargo.shop.spi.schedule.ScheduleWindowsPort;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.ServiceName;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Only the independent beauty server imports these adapters; embedded keeps its platform-owned local facts. */
@Configuration(proxyBeanMethods = false)
public class BeautyMerchantClientConfiguration {
    @Bean
    @ConditionalOnMissingBean(PersonnelFactsPort.class)
    PersonnelFactsPort remotePersonnelFactsPort(InternalHttp http) {
        return new RemotePersonnelFactsPort(api(http));
    }
    @Bean
    @ConditionalOnMissingBean(ResourceFactsPort.class)
    ResourceFactsPort remoteResourceFactsPort(InternalHttp http) {
        return new RemoteResourceFactsPort(api(http));
    }
    @Bean
    @ConditionalOnMissingBean(ScheduleWindowsPort.class)
    ScheduleWindowsPort remoteScheduleWindowsPort(InternalHttp http) {
        return new RemoteScheduleWindowsPort(api(http));
    }
    private MerchantFactsInternalApi api(InternalHttp http) {
        return http.client(ServiceName.PLATFORM, MerchantFactsInternalApi.class, Duration.ofSeconds(5));
    }
}

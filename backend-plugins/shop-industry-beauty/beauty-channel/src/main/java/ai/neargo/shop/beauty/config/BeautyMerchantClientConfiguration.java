package ai.neargo.shop.beauty.config;

import ai.neargo.shop.beauty.port.RemoteMerchantOperatorAccessPort;
import ai.neargo.shop.beauty.port.RemoteResourceBookingPort;
import ai.neargo.shop.beauty.port.RemoteResourceCandidatesPort;
import ai.neargo.shop.beauty.port.RemoteCustomerIdentityPort;
import ai.neargo.shop.beauty.port.RemoteGuestRegistrationPort;
import ai.neargo.shop.spi.member.GuestRegistrationPort;
import ai.neargo.shop.spi.member.GuestRegistrationInternalApi;
import ai.neargo.shop.spi.resource.ResourceBookingPort;
import ai.neargo.shop.spi.resource.ResourceCandidatesPort;
import ai.neargo.shop.spi.resource.ResourceBookingInternalApi;
import ai.neargo.shop.spi.member.CustomerIdentityPort;
import ai.neargo.shop.spi.member.CustomerIdentityInternalApi;
import ai.neargo.shop.beauty.port.RemoteResourceCommandFactsPort;
import ai.neargo.shop.spi.user.MerchantOperatorAccessPort;
import ai.neargo.shop.spi.resource.ResourceCommandFactsPort;
import ai.neargo.shop.spi.resource.ResourceRecoveryInternalApi;
import ai.neargo.shop.beauty.port.RemotePersonnelFactsPort;
import ai.neargo.shop.beauty.port.RemotePersonnelOperatorFactsPort;
import ai.neargo.shop.spi.personnel.PersonnelOperatorFactsPort;
import ai.neargo.shop.spi.personnel.PersonnelOperatorInternalApi;
import ai.neargo.shop.svc.InternalBindings;
import org.springframework.beans.factory.annotation.Value;
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
    @ConditionalOnMissingBean(PersonnelOperatorFactsPort.class)
    PersonnelOperatorFactsPort remotePersonnelOperatorFactsPort(
            InternalHttp http, @Value("${shop.services.internal-token:}") String token) {
        return new RemotePersonnelOperatorFactsPort(
                http.client(
                        ServiceName.PLATFORM,
                        PersonnelOperatorInternalApi.class,
                        Duration.ofSeconds(5)),
                new InternalBindings(token));
    }

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
    @Bean
    @ConditionalOnMissingBean(MerchantOperatorAccessPort.class)
    MerchantOperatorAccessPort remoteMerchantOperatorAccessPort(InternalHttp http) {
        return new RemoteMerchantOperatorAccessPort(recoveryApi(http));
    }
    @Bean
    @ConditionalOnMissingBean(ResourceCommandFactsPort.class)
    ResourceCommandFactsPort remoteResourceCommandFactsPort(InternalHttp http) {
        return new RemoteResourceCommandFactsPort(recoveryApi(http));
    }
    private ResourceRecoveryInternalApi recoveryApi(InternalHttp http) {
        return http.client(ServiceName.PLATFORM, ResourceRecoveryInternalApi.class, Duration.ofSeconds(5));
    }
    private MerchantFactsInternalApi api(InternalHttp http) {
        return http.client(ServiceName.PLATFORM, MerchantFactsInternalApi.class, Duration.ofSeconds(5));
    }
    @Bean
    @ConditionalOnMissingBean(ResourceBookingPort.class)
    ResourceBookingPort beautyRemoteResourceBookingPort(InternalHttp http) {
        return new RemoteResourceBookingPort(bookingApi(http));
    }
    @Bean
    @ConditionalOnMissingBean(ResourceCandidatesPort.class)
    ResourceCandidatesPort beautyRemoteResourceCandidatesPort(InternalHttp http) {
        return new RemoteResourceCandidatesPort(bookingApi(http));
    }
    @Bean
    @ConditionalOnMissingBean(CustomerIdentityPort.class)
    CustomerIdentityPort beautyRemoteCustomerIdentityPort(InternalHttp http) {
        return new RemoteCustomerIdentityPort(http.client(ServiceName.PLATFORM, CustomerIdentityInternalApi.class,
                Duration.ofSeconds(5)));
    }
    @Bean
    @ConditionalOnMissingBean(GuestRegistrationPort.class)
    GuestRegistrationPort beautyRemoteGuestRegistrationPort(InternalHttp http) {
        return new RemoteGuestRegistrationPort(http.client(ServiceName.PLATFORM, GuestRegistrationInternalApi.class,
                Duration.ofSeconds(5)));
    }
    private ResourceBookingInternalApi bookingApi(InternalHttp http) {
        return http.client(ServiceName.PLATFORM, ResourceBookingInternalApi.class, Duration.ofSeconds(5));
    }
}

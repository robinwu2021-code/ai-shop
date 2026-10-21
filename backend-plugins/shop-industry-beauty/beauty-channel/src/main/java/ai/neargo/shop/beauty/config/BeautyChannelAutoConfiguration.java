package ai.neargo.shop.beauty.config;

import ai.neargo.shop.beauty.spi.BeautyBookingInternalApi;
import ai.neargo.shop.beauty.spi.BeautySourceInternalApi;
import ai.neargo.shop.beauty.port.RemoteAppointmentSourceAuthorityPort;
import ai.neargo.shop.beauty.client.RemoteAppointmentAvailabilityService;
import ai.neargo.shop.beauty.client.RemoteAppointmentService;
import ai.neargo.shop.beauty.client.RemoteServiceOperationQueryService;
import ai.neargo.shop.beauty.client.RemoteServiceOperationRecoveryService;
import ai.neargo.shop.beauty.service.AppointmentAvailabilityService;
import ai.neargo.shop.beauty.service.AppointmentService;
import ai.neargo.shop.beauty.service.ServiceOperationQueryService;
import ai.neargo.shop.beauty.service.ServiceOperationRecoveryService;
import ai.neargo.shop.beauty.client.RemoteStorePolicyService;
import ai.neargo.shop.beauty.client.RemoteServiceCatalogQueryService;
import ai.neargo.shop.beauty.service.ServiceCatalogQueryService;
import ai.neargo.shop.beauty.spi.BeautyCatalogInternalApi;
import ai.neargo.shop.beauty.client.RemoteServiceDefinitionService;
import ai.neargo.shop.beauty.service.StorePolicyService;
import ai.neargo.shop.beauty.service.ServiceDefinitionService;
import ai.neargo.shop.beauty.spi.BeautyPolicyInternalApi;
import ai.neargo.shop.beauty.spi.BeautyDefinitionInternalApi;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.ServiceName;
import java.time.Duration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/** Loaded only with standalone channel artifacts; no dependency on beauty-core or its tables. */
@AutoConfiguration
@ConditionalOnBean(InternalHttp.class)
@ConditionalOnMissingClass("ai.neargo.shop.beauty.config.BeautyAutoConfiguration")
@ConditionalOnProperty(prefix = "shop.beauty", name = "enabled", havingValue = "true")
public class BeautyChannelAutoConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "shop.beauty", name = "deployment", havingValue = "standalone")
    @ConditionalOnMissingBean(StorePolicyService.class)
    StorePolicyService remoteStorePolicyService(InternalHttp http) {
        return new RemoteStorePolicyService(http.client(ServiceName.BEAUTY, BeautyPolicyInternalApi.class,
                Duration.ofSeconds(5)));
    }
    @Bean
    @ConditionalOnProperty(prefix = "shop.beauty", name = "deployment", havingValue = "standalone")
    @ConditionalOnMissingBean(ServiceDefinitionService.class)
    ServiceDefinitionService remoteServiceDefinitionService(InternalHttp http) {
        return new RemoteServiceDefinitionService(http.client(ServiceName.BEAUTY, BeautyDefinitionInternalApi.class,
                Duration.ofSeconds(5)));
    }
    @Bean
    @ConditionalOnProperty(prefix = "shop.beauty", name = "deployment", havingValue = "standalone")
    @ConditionalOnMissingBean(ServiceCatalogQueryService.class)
    ServiceCatalogQueryService remoteServiceCatalogQueryService(InternalHttp http) {
        return new RemoteServiceCatalogQueryService(http.client(ServiceName.BEAUTY, BeautyCatalogInternalApi.class,
                Duration.ofSeconds(5)));
    }
    @Bean
    @ConditionalOnProperty(prefix = "shop.beauty", name = "deployment", havingValue = "standalone")
    @ConditionalOnMissingBean(AppointmentAvailabilityService.class)
    AppointmentAvailabilityService beautyRemoteAppointmentAvailabilityService(InternalHttp http) {
        return new RemoteAppointmentAvailabilityService(http.client(ServiceName.BEAUTY, BeautyBookingInternalApi.class,
                Duration.ofSeconds(5)));
    }
    @Bean
    @ConditionalOnProperty(prefix = "shop.beauty", name = "deployment", havingValue = "standalone")
    @ConditionalOnMissingBean(AppointmentService.class)
    AppointmentService beautyRemoteAppointmentService(InternalHttp http) {
        return new RemoteAppointmentService(http.client(ServiceName.BEAUTY, BeautyBookingInternalApi.class,
                Duration.ofSeconds(5)));
    }
    @Bean
    @ConditionalOnProperty(prefix = "shop.beauty", name = "deployment", havingValue = "standalone")
    @ConditionalOnMissingBean(ServiceOperationQueryService.class)
    ServiceOperationQueryService beautyRemoteServiceOperationQueryService(InternalHttp http) {
        return new RemoteServiceOperationQueryService(http.client(ServiceName.BEAUTY, BeautyBookingInternalApi.class,
                Duration.ofSeconds(5)));
    }
    @Bean
    @ConditionalOnProperty(prefix = "shop.beauty", name = "deployment", havingValue = "standalone")
    @ConditionalOnMissingBean(ServiceOperationRecoveryService.class)
    ServiceOperationRecoveryService beautyRemoteServiceOperationRecoveryService(InternalHttp http) {
        return new RemoteServiceOperationRecoveryService(http.client(ServiceName.BEAUTY, BeautyBookingInternalApi.class,
                Duration.ofSeconds(5)));
    }
    @Bean
    @ConditionalOnProperty(prefix = "shop.beauty", name = "deployment", havingValue = "standalone")
    RemoteAppointmentSourceAuthorityPort beautyRemoteAppointmentSourceAuthorityPort(InternalHttp http) {
        return new RemoteAppointmentSourceAuthorityPort(http.client(ServiceName.BEAUTY,
                BeautySourceInternalApi.class, Duration.ofSeconds(5)));
    }
}

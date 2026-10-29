package ai.neargo.shop.beauty.config;

import ai.neargo.shop.beauty.port.RemoteCheckoutPort;
import ai.neargo.shop.beauty.port.RemoteServiceExecutionPreparationPort;
import ai.neargo.shop.spi.trade.ServiceExecutionPreparationPort;
import ai.neargo.shop.spi.trade.ServiceExecutionPreparationInternalApi;
import ai.neargo.shop.beauty.port.RemoteServiceExecutionPort;
import ai.neargo.shop.spi.trade.ServiceExecutionPort;
import ai.neargo.shop.spi.trade.ServiceExecutionInternalApi;
import ai.neargo.shop.beauty.port.RemoteManualServiceAdmissionPort;
import ai.neargo.shop.spi.trade.CheckoutInternalApi;
import ai.neargo.shop.spi.trade.CheckoutPort;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionInternalApi;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort;
import ai.neargo.shop.svc.InternalBindings;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.ServiceName;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Only the private SERVER imports this provider; embedded checkout remains with the real HOST
 * owner.
 */
@Configuration(proxyBeanMethods = false)
public class BeautyCheckoutClientConfiguration {
    /** Missing service targets fail on use; an empty owner is never installed. */
    @Bean
    @ConditionalOnMissingBean(CheckoutPort.class)
    CheckoutPort beautyRemoteCheckoutPort(
            InternalHttp http, @Value("${shop.services.internal-token:}") String token) {
        return new RemoteCheckoutPort(
                http.client(ServiceName.PLATFORM, CheckoutInternalApi.class, Duration.ofSeconds(5)),
                new InternalBindings(token));
    }

    /** Standalone forms get a strict bound remote owner; embedded forms retain actual Core. */
    @Bean
    @ConditionalOnMissingBean(ManualServiceAdmissionPort.class)
    ManualServiceAdmissionPort beautyRemoteManualServiceAdmissionPort(
            InternalHttp http, @Value("${shop.services.internal-token:}") String token) {
        return new RemoteManualServiceAdmissionPort(
                http.client(
                        ServiceName.PLATFORM,
                        ManualServiceAdmissionInternalApi.class,
                        Duration.ofSeconds(5)),
                new InternalBindings(token));
    }

    /** Standalone beauty consumes the same real Core quantity owner, without local grant persistence. */
    @Bean
    @ConditionalOnMissingBean(ServiceExecutionPort.class)
    ServiceExecutionPort beautyRemoteServiceExecutionPort(InternalHttp http,
            @Value("${shop.services.internal-token:}") String token) {
        return new RemoteServiceExecutionPort(http.client(ServiceName.PLATFORM,
                ServiceExecutionInternalApi.class, Duration.ofSeconds(5)), new InternalBindings(token));
    }

    /** Only standalone SERVER installs the finite real preparation bridge. */
    @Bean
    @ConditionalOnMissingBean(ServiceExecutionPreparationPort.class)
    ServiceExecutionPreparationPort beautyRemotePreparationPort(
            InternalHttp http, @Value("${shop.services.internal-token:}") String token) {
        return new RemoteServiceExecutionPreparationPort(
                http.client(
                        ServiceName.PLATFORM,
                        ServiceExecutionPreparationInternalApi.class,
                        Duration.ofSeconds(5)),
                token);
    }
}

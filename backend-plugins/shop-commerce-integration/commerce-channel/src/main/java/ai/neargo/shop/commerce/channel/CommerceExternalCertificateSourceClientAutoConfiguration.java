package ai.neargo.shop.commerce.channel;

import ai.neargo.shop.spi.entitlement.ExternalCertificateConsumptionInternalApi;
import ai.neargo.shop.svc.ConfigServiceLocator;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.ServiceName;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Lazy;

import java.time.Duration;

/**
 * Explicit standalone source mode calls the genuine PLATFORM owner; it never silently substitutes
 * for an embedded owner.
 */
@AutoConfiguration(
        beforeName = "ai.neargo.shop.commerce.config.CommerceRedemptionAutoConfiguration")
@ConditionalOnProperty(
        name = "shop.commerce.external-certificate-source-client.enabled",
        havingValue = "true")
@EnableConfigurationProperties(ConfigServiceLocator.class)
@Import(InternalHttp.class)
public class CommerceExternalCertificateSourceClientAutoConfiguration {
    @Bean
    @Lazy
    ExternalCertificateConsumptionInternalApi externalCertificateConsumptionInternalApi(
            InternalHttp http) {
        return http.client(
                ServiceName.PLATFORM,
                ExternalCertificateConsumptionInternalApi.class,
                Duration.ofSeconds(5));
    }

    @Bean
    RemoteExternalCertificateConsumptionPort remoteExternalCertificateConsumptionPort(
            ObjectProvider<ExternalCertificateConsumptionInternalApi> api) {
        return new RemoteExternalCertificateConsumptionPort(api::getObject);
    }
}

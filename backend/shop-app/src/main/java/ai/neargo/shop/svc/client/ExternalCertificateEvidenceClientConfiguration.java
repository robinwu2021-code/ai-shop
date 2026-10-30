package ai.neargo.shop.svc.client;

import ai.neargo.shop.spi.entitlement.ExternalCertificateEvidenceInternalApi;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.ServiceName;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import java.time.Duration;

/**
 * Explicit standalone evidence mode; enabling both local and remote original owners is rejected by
 * source owner uniqueness checks.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        name = "shop.entitlement.external-certificate.evidence-client.enabled",
        havingValue = "true")
public class ExternalCertificateEvidenceClientConfiguration {
    @Bean
    @Lazy
    ExternalCertificateEvidenceInternalApi externalCertificateEvidenceInternalApi(
            InternalHttp http) {
        return http.client(
                ServiceName.COMMERCE,
                ExternalCertificateEvidenceInternalApi.class,
                Duration.ofSeconds(5));
    }

    @Bean
    RemoteExternalCertificateEvidencePort remoteExternalCertificateEvidencePort(
            ObjectProvider<ExternalCertificateEvidenceInternalApi> api) {
        return new RemoteExternalCertificateEvidencePort(api::getObject);
    }
}

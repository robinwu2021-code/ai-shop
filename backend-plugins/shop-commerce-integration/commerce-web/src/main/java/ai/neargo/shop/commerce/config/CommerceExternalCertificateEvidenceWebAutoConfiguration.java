package ai.neargo.shop.commerce.config;

import ai.neargo.shop.commerce.api.internal.InternalExternalCertificateEvidenceController;
import ai.neargo.shop.spi.entitlement.ExternalCertificateEvidencePort;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

/** Internal receipt access exists only with the real default-off redemption owner. */
@AutoConfiguration(afterName = "ai.neargo.shop.commerce.config.CommerceRedemptionAutoConfiguration")
@ConditionalOnProperty(name = "shop.commerce.redemption.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class CommerceExternalCertificateEvidenceWebAutoConfiguration {
    @Bean
    @ConditionalOnBean(ExternalCertificateEvidencePort.class)
    InternalExternalCertificateEvidenceController internalExternalCertificateEvidenceController(
            @Value("${shop.services.internal-token:}") String token,
            ExternalCertificateEvidencePort owner) {
        return new InternalExternalCertificateEvidenceController(token, owner);
    }
}

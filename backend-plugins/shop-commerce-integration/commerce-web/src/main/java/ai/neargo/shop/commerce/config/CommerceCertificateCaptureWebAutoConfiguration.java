package ai.neargo.shop.commerce.config;

import ai.neargo.shop.commerce.api.internal.InternalChannelCertificateCaptureController;
import ai.neargo.shop.spi.commerce.ChannelCertificateVaultPort;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

/** Private capture exists only with the actual encrypted owner and explicit capture opt-in. */
@AutoConfiguration(
        afterName = "ai.neargo.shop.commerce.channel.CommerceCertificateVaultAutoConfiguration")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(
        name = "shop.commerce.certificate-vault.capture.enabled",
        havingValue = "true")
public class CommerceCertificateCaptureWebAutoConfiguration {
    @Bean
    @ConditionalOnBean(ChannelCertificateVaultPort.class)
    InternalChannelCertificateCaptureController internalChannelCertificateCaptureController(
            @Value("${shop.services.internal-token:}") String token,
            ChannelCertificateVaultPort vault) {
        return new InternalChannelCertificateCaptureController(token, vault);
    }
}

package ai.neargo.shop.commerce.channel;

import ai.neargo.secret.api.SecretVault;
import ai.neargo.shop.spi.commerce.ChannelCertificateHandleStore;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/** Vault capture requires a real encrypted SecretVault owner and actual scoped handle metadata. */
@AutoConfiguration(afterName = "ai.neargo.shop.commerce.config.CommerceRedemptionAutoConfiguration")
@ConditionalOnProperty(
        name = {
            "shop.commerce.enabled",
            "shop.commerce.redemption.enabled",
            "shop.commerce.certificate-vault.enabled"
        },
        havingValue = "true")
public class CommerceCertificateVaultAutoConfiguration {
    @Bean
    @ConditionalOnBean({SecretVault.class, ChannelCertificateHandleStore.class})
    SecretVaultChannelCertificateAdapter channelCertificateVault(
            SecretVault vault, ChannelCertificateHandleStore handles) {
        return new SecretVaultChannelCertificateAdapter(vault, handles);
    }
}

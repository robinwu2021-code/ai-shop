package ai.neargo.shop.commerce.channel;

import ai.neargo.shop.commerce.channel.mock.MockCommerceGateway;
import ai.neargo.shop.spi.commerce.ChannelCertificateVaultPort;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Mock adapters may open only a genuine in-process encrypted certificate owner; absent owners
 * remain unsupported.
 */
@AutoConfiguration(
        after = CommerceCertificateVaultAutoConfiguration.class,
        afterName = "ai.neargo.shop.commerce.channel.CommerceChannelAutoConfiguration")
@ConditionalOnProperty(name = "shop.commerce.redemption.enabled", havingValue = "true")
public class CommerceMockCertificateVaultBindingAutoConfiguration {
    @Bean
    SmartInitializingSingleton bindMockCertificateVault(
            ObjectProvider<MockCommerceGateway> gateways,
            ObjectProvider<ChannelCertificateVaultPort> vaults) {
        return () ->
                gateways.orderedStream()
                        .forEach(gateway -> gateway.bindCertificateVault(vaults::getIfUnique));
    }
}

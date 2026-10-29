package ai.neargo.shop.commerce.config;

import ai.neargo.shop.commerce.mapper.CertificateHandleMapper;
import ai.neargo.shop.commerce.mapper.CommerceMappers.ResourceMappingMapper;
import ai.neargo.shop.commerce.mapper.RedemptionItemMapper;
import ai.neargo.shop.commerce.mapper.RedemptionLinkMapper;
import ai.neargo.shop.commerce.mapper.RedemptionPreviewMapper;
import ai.neargo.shop.commerce.mapper.RedemptionReversalMapper;
import ai.neargo.shop.commerce.service.impl.CatalogPersistence;
import ai.neargo.shop.commerce.service.impl.ChannelCertificateHandleStoreImpl;
import ai.neargo.shop.commerce.service.impl.ChannelRedemptionServiceImpl;
import ai.neargo.shop.spi.commerce.ChannelCertificateHandleStore;
import ai.neargo.shop.spi.commerce.ChannelRedemptionGateway;
import ai.neargo.shop.spi.commerce.ChannelResourceMappingPort;
import ai.neargo.shop.spi.entitlement.ExternalCertificateConsumptionPort;
import ai.neargo.shop.spi.user.MerchantQueryPort;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import java.time.Clock;

/**
 * External issuer use is opt-in and contributes to the existing recovery job, without enabling
 * local entitlement assets.
 */
@AutoConfiguration(after = CommerceCoreAutoConfiguration.class)
@ConditionalOnProperty(
        name = {"shop.commerce.enabled", "shop.commerce.redemption.enabled"},
        havingValue = "true")
public class CommerceRedemptionAutoConfiguration {
    @Bean
    ChannelCertificateHandleStoreImpl channelCertificateHandleStore(
            CatalogPersistence db,
            CertificateHandleMapper handles,
            ObjectProvider<MerchantQueryPort> merchants) {
        return new ChannelCertificateHandleStoreImpl(db, handles, merchants::getIfUnique);
    }

    @Bean
    ChannelRedemptionServiceImpl channelRedemptionService(
            CatalogPersistence db,
            RedemptionPreviewMapper previews,
            RedemptionLinkMapper links,
            RedemptionItemMapper items,
            RedemptionReversalMapper reversals,
            ResourceMappingMapper mappings,
            ChannelResourceMappingPort mapping,
            ChannelCertificateHandleStore handles,
            ObjectProvider<ExternalCertificateConsumptionPort> owners,
            ObjectProvider<ChannelRedemptionGateway> gateways,
            ObjectProvider<Clock> clocks) {
        return new ChannelRedemptionServiceImpl(
                db,
                previews,
                links,
                items,
                reversals,
                mappings,
                mapping,
                handles,
                () -> owners.orderedStream().toList(),
                gateways.orderedStream().toList(),
                clocks.getIfAvailable(Clock::systemUTC));
    }
}

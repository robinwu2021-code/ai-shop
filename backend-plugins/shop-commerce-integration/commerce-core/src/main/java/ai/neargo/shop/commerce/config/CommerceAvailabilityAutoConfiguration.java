package ai.neargo.shop.commerce.config;

import ai.neargo.shop.commerce.mapper.AvailabilityPublicationMapper;
import ai.neargo.shop.commerce.mapper.CommerceMappers.ResourceMappingMapper;
import ai.neargo.shop.commerce.port.InventoryChannelAvailabilitySource;
import ai.neargo.shop.commerce.service.impl.CatalogPersistence;
import ai.neargo.shop.commerce.service.impl.ChannelAvailabilityServiceImpl;
import ai.neargo.shop.spi.commerce.ChannelAvailabilityGateway;
import ai.neargo.shop.spi.commerce.ChannelAvailabilitySourcePort;
import ai.neargo.shop.spi.commerce.ChannelConnectionIdentityPort;
import ai.neargo.shop.spi.commerce.ChannelResourceMappingPort;
import ai.neargo.shop.spi.inventory.InventoryAvailabilityPort;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import java.time.Clock;

@AutoConfiguration(after = CommerceCoreAutoConfiguration.class)
@ConditionalOnProperty(prefix = "shop.commerce", name = "enabled", havingValue = "true")
public class CommerceAvailabilityAutoConfiguration {
    @Bean
    @ConditionalOnBean(InventoryAvailabilityPort.class)
    @ConditionalOnProperty(
            prefix = "shop.inventory",
            name = "stock-authority",
            havingValue = "INVENTORY")
    InventoryChannelAvailabilitySource inventoryChannelAvailabilitySource(
            InventoryAvailabilityPort inventory) {
        return new InventoryChannelAvailabilitySource(inventory);
    }

    @Bean
    ChannelAvailabilityServiceImpl channelAvailabilityService(
            CatalogPersistence db,
            AvailabilityPublicationMapper publications,
            ResourceMappingMapper mappings,
            ChannelResourceMappingPort mappingAuthority,
            ObjectProvider<ChannelAvailabilitySourcePort> sources,
            ObjectProvider<ChannelAvailabilityGateway> gateways,
            ObjectProvider<Clock> clocks,
            ObjectProvider<ChannelConnectionIdentityPort> identities) {
        return new ChannelAvailabilityServiceImpl(
                db,
                publications,
                mappings,
                mappingAuthority,
                () -> sources.orderedStream().toList(),
                gateways.orderedStream().toList(),
                clocks.getIfAvailable(Clock::systemUTC),
                identities::getIfUnique);
    }
}

package ai.neargo.shop.commerce.config;

import ai.neargo.shop.commerce.mapper.AfterSaleMappers;
import ai.neargo.shop.commerce.service.impl.ChannelAfterSaleServiceImpl;
import ai.neargo.shop.commerce.service.impl.ChannelOrderServiceImpl;
import ai.neargo.shop.spi.commerce.ChannelAfterSaleFundsPort;
import ai.neargo.shop.spi.commerce.ChannelAfterSaleGateway;
import ai.neargo.shop.spi.commerce.ChannelAfterSaleSourcePort;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import java.time.Clock;
import java.util.HashSet;

@AutoConfiguration(after = CommerceCoreAutoConfiguration.class)
@ConditionalOnProperty(name = "shop.commerce.enabled", havingValue = "true")
public class CommerceAfterSaleAutoConfiguration {
    @Bean
    ChannelAfterSaleServiceImpl channelAfterSaleService(
            ChannelOrderServiceImpl orders,
            AfterSaleMappers.LinkMapper links,
            AfterSaleMappers.RevisionMapper revisions,
            AfterSaleMappers.LineMapper lines,
            ObjectProvider<ChannelAfterSaleSourcePort> sources,
            ObjectProvider<ChannelAfterSaleFundsPort> funds,
            ObjectProvider<ChannelAfterSaleGateway> gateways,
            ObjectProvider<Clock> clocks) {
        return new ChannelAfterSaleServiceImpl(
                orders,
                links,
                revisions,
                lines,
                () -> sources.orderedStream().toList(),
                () -> funds.orderedStream().toList(),
                gateways.orderedStream().toList(),
                clocks.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    ApplicationRunner originalAfterSaleOwnersAreUnique(
            ObjectProvider<ChannelAfterSaleSourcePort> sources,
            ObjectProvider<ChannelAfterSaleFundsPort> funds) {
        return args -> {
            var owners = new HashSet<String>();
            for (var source : sources.orderedStream().toList())
                if (!owners.add(source.owner().name()))
                    throw new IllegalStateException("Duplicate original after-sale source owner");
            if (funds.orderedStream().limit(2).count() > 1)
                throw new IllegalStateException("Duplicate original after-sale funds owner");
        };
    }
}

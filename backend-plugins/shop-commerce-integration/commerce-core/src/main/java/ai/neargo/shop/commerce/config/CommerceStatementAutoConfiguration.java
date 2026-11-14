package ai.neargo.shop.commerce.config;

import ai.neargo.shop.commerce.mapper.StatementLineMapper;
import ai.neargo.shop.commerce.mapper.StatementMapper;
import ai.neargo.shop.commerce.service.impl.CatalogPersistence;
import ai.neargo.shop.commerce.service.impl.ChannelReconciliationServiceImpl;
import ai.neargo.shop.spi.commerce.ChannelStatementGateway;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import java.time.Clock;

/** Explicit technical reconciliation contributes only to the existing recovery job. */
@AutoConfiguration(after = CommerceCoreAutoConfiguration.class)
@ConditionalOnProperty(
        name = {"shop.commerce.enabled", "shop.commerce.statement.enabled"},
        havingValue = "true")
public class CommerceStatementAutoConfiguration {
    @Bean
    ChannelReconciliationServiceImpl channelReconciliationService(
            CatalogPersistence db,
            StatementMapper statements,
            StatementLineMapper lines,
            ObjectProvider<ChannelStatementGateway> gateways,
            ObjectProvider<Clock> clocks) {
        return new ChannelReconciliationServiceImpl(
                db,
                statements,
                lines,
                gateways.orderedStream().toList(),
                clocks.getIfAvailable(Clock::systemUTC));
    }
}

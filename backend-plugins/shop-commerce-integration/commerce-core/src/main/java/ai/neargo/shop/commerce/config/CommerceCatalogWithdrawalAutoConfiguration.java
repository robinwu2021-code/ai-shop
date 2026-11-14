package ai.neargo.shop.commerce.config;

import ai.neargo.shop.commerce.mapper.SourceWithdrawalMapper;
import ai.neargo.shop.commerce.service.impl.CatalogPersistence;
import ai.neargo.shop.commerce.service.impl.SourceWithdrawalStore;
import ai.neargo.shop.spi.commerce.ChannelSourceWithdrawalProvider;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import java.util.HashSet;

/**
 * New clear/withdrawal branches are entirely default-off and reuse the original
 * publication/recovery engine.
 */
@AutoConfiguration(after = CommerceCoreAutoConfiguration.class)
@ConditionalOnProperty(
        name = {"shop.commerce.enabled", "shop.commerce.catalog.withdrawal.enabled"},
        havingValue = "true")
public class CommerceCatalogWithdrawalAutoConfiguration {
    @Bean
    SourceWithdrawalStore sourceWithdrawalStore(
            CatalogPersistence db,
            SourceWithdrawalMapper rows,
            ObjectProvider<ChannelSourceWithdrawalProvider> providers) {
        var store = new SourceWithdrawalStore(db, rows);
        var seen = new HashSet<String>();
        for (var provider : providers.orderedStream().toList())
            if (!seen.add(provider.owner().name()))
                throw new IllegalStateException("Duplicate original withdrawal owner");
        db.withdrawalSupport(true, () -> providers.orderedStream().toList(), store);
        return store;
    }
}

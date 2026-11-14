package ai.neargo.shop.commerce.config;

import ai.neargo.shop.commerce.mapper.TaxSourceNoticeMapper;
import ai.neargo.shop.commerce.service.SourceNoticeService;
import ai.neargo.shop.commerce.service.impl.CatalogPersistence;
import ai.neargo.shop.commerce.service.impl.TaxSourceNoticeServiceImpl;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(after = CommerceCoreAutoConfiguration.class)
@ConditionalOnProperty(name = "shop.commerce.enabled", havingValue = "true")
public class CommerceTaxSourceNoticeAutoConfiguration {
    @Bean
    TaxSourceNoticeServiceImpl taxSourceNoticeService(
            CatalogPersistence db, TaxSourceNoticeMapper rows, SourceNoticeService targets) {
        return new TaxSourceNoticeServiceImpl(db, rows, targets);
    }
}

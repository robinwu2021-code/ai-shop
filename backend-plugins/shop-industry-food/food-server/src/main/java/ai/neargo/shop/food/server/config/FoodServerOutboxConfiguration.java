package ai.neargo.shop.food.server.config;

import ai.neargo.shop.event.outbox.runtime.JdbcOutboxStore;
import ai.neargo.shop.event.outbox.runtime.OutboxProperties;
import ai.neargo.shop.event.outbox.runtime.UnifiedOutboxFacade;
import ai.neargo.shop.food.config.ConditionalOnFoodEnabled;
import ai.neargo.shop.food.config.FoodPersistenceConfiguration.PersistenceEnabled;
import ai.neargo.shop.spi.food.FoodOutboxRouting;
import java.util.HashSet;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/** Standalone FOOD publishes into the existing physical PLATFORM source; PLATFORM alone delivers it. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnFoodEnabled
@Conditional(PersistenceEnabled.class)
@ConditionalOnProperty(prefix="shop.food",name="deployment",havingValue="standalone")
@EnableConfigurationProperties(OutboxProperties.class)
public class FoodServerOutboxConfiguration {
    @Bean
    @ConditionalOnMissingBean(UnifiedOutboxFacade.class)
    UnifiedOutboxFacade foodServerOutboxFacade(@Qualifier("dataSource") DataSource source,
            @Qualifier("transactionManager") PlatformTransactionManager manager,ObjectMapper json,
            OutboxProperties properties) {
        properties.validate();
        if(!properties.unified("PLATFORM") || properties.unified("INVENTORY")) {
            throw new IllegalStateException("Standalone FOOD requires the existing unified PLATFORM producer source only");
        }
        if(!(manager instanceof DataSourceTransactionManager jdbc) || jdbc.getDataSource()!=source) {
            throw new IllegalStateException("FOOD outbox must use the same primary business transaction and data source");
        }
        var facade=new UnifiedOutboxFacade(json);
        facade.setMaintenance(properties.isMaintenance()); facade.setRetryPolicy(properties.retryPolicy());
        var archive=new HashSet<>(properties.getArchiveOnly()); archive.addAll(FoodOutboxRouting.ARCHIVE_ONLY);
        facade.setArchiveOnly(archive);
        facade.registerProducer(new JdbcOutboxStore(JdbcOutboxStore.Source.PLATFORM,source,manager,json),
                FoodOutboxRouting.DELIVERY_TARGETS);
        // No OutboxRunner, signal pump, consumer registry or delivery scheduler is created in this host.
        return facade;
    }
}

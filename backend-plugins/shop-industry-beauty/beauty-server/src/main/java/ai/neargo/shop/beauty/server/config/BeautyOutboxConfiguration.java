package ai.neargo.shop.beauty.server.config;

import ai.neargo.shop.beauty.config.ConditionalOnBeautyEnabled;
import ai.neargo.shop.event.outbox.runtime.JdbcOutboxStore;
import ai.neargo.shop.event.outbox.runtime.OutboxProperties;
import ai.neargo.shop.event.outbox.runtime.UnifiedOutboxFacade;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/** Reuses the existing PLATFORM store on the primary database; only explicit archive contracts are served here. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBeautyEnabled
@EnableConfigurationProperties(OutboxProperties.class)
public class BeautyOutboxConfiguration {
    @Bean
    @ConditionalOnMissingBean(UnifiedOutboxFacade.class)
    UnifiedOutboxFacade beautyUnifiedOutboxFacade(DataSource source, PlatformTransactionManager manager,
            ObjectMapper json, OutboxProperties properties) {
        properties.validate();
        var facade = new UnifiedOutboxFacade(json);
        facade.setArchiveOnly(properties.getArchiveOnly());
        facade.setMaintenance(properties.isMaintenance());
        facade.setRetryPolicy(properties.retryPolicy());
        if (properties.unified("PLATFORM")) {
            facade.register(new JdbcOutboxStore(JdbcOutboxStore.Source.PLATFORM, source, manager, json), List.of());
        }
        return facade;
    }
}

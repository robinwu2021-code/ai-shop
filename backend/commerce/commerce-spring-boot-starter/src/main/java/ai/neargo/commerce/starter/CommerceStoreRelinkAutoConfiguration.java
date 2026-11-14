package ai.neargo.commerce.starter;

import ai.neargo.commerce.api.subscription.StoreRelinkFacade;
import ai.neargo.commerce.core.subscription.StoreRelinkService;
import ai.neargo.commerce.persistence.JdbcStoreRelinkRepository;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.DependsOn;

@AutoConfiguration(after=CommercePersistenceAutoConfiguration.class)
@ConditionalOnProperty(prefix="commerce",name="enabled",havingValue="true")
public class CommerceStoreRelinkAutoConfiguration {
    @Bean
    @ConditionalOnBean(StoreRelinkService.Authority.class)
    @ConditionalOnMissingBean(StoreRelinkFacade.class)
    @DependsOn("commerceMigrationInitializer")
    StoreRelinkFacade commerceStoreRelink(DataSource ds, StoreRelinkService.Authority authority) {
        return new StoreRelinkService(new JdbcStoreRelinkRepository(ds),authority);
    }
}

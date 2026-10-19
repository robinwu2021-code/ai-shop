package ai.neargo.shop.scenario;

import ai.neargo.shop.config.OutboxConfiguration;
import ai.neargo.shop.event.OutboxConsumer;
import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.event.outbox.runtime.UnifiedOutboxFacade;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import static org.assertj.core.api.Assertions.assertThat;

class OutboxConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(OutboxConfiguration.class, Dependencies.class)
            .withPropertyValues("shop.outbox.enabled=false");
    @Test
    void legacyModeLeavesSourcesUnclaimed() {
        context.run(c -> assertThat(c.getBean(UnifiedOutboxFacade.class).stores()).isEmpty());
    }
    @Test
    void unifiedPlatformRegistersWithoutEagerPublisherConsumerCycle() {
        context.withPropertyValues("shop.outbox.sources.platform.mode=unified").run(c -> {
            assertThat(c).hasNotFailed();
            assertThat(c.getBean(UnifiedOutboxFacade.class).enabled("PLATFORM")).isTrue();
        });
    }
    @Test
    void twoSendersCannotOwnTheSameSource() {
        context.withPropertyValues("shop.outbox.sources.platform.mode=unified", "shop.outbox.legacy-scan.enabled=true")
                .run(c -> assertThat(c).hasFailed());
    }
    @Configuration
    static class Dependencies {
        @Bean ObjectMapper json() { return new ObjectMapper(); }
        @Bean DataSource dataSource() {
            var ds = new DriverManagerDataSource("jdbc:h2:mem:outbox-config-" + java.util.UUID.randomUUID(), "sa", "");
            // Keep the database alive across the short connection used by the startup history check.
            ds.setUrl(ds.getUrl() + ";DB_CLOSE_DELAY=-1");
            new JdbcTemplate(ds).execute("CREATE TABLE sys_outbox (source_system VARCHAR(32),status VARCHAR(16))");
            return ds;
        }
        @Bean PlatformTransactionManager transactionManager(DataSource ds) { return new DataSourceTransactionManager(ds); }
        @Bean OutboxConsumer consumer() {
            return new OutboxConsumer() {
                public String handlerId() { return "test-local"; }
                public boolean supports(String type) { return true; }
                public void consume(SysOutbox e) { }
            };
        }
    }
}

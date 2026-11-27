package ai.neargo.commerce.starter;

import static org.junit.jupiter.api.Assertions.*;
import ai.neargo.commerce.api.mall.reporting.PurchaseOrderQueryPort;
import ai.neargo.commerce.api.port.*;
import ai.neargo.commerce.core.spi.CommerceUnitOfWork;
import ai.neargo.commerce.persistence.*;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class CommerceMallDatabaseWiringTest {
    @TempDir Path hostMigrations;

    @Test
    void buyerHistoryAndRollbackUseTheConfiguredMallPool() throws Exception {
        try (var mall = new CommerceMigrationStartupTest.Database();
                var foundation = new CommerceMigrationStartupTest.Database()) {
            var schema = new ResourceDatabasePopulator(new ClassPathResource("db/mall/order/V1__mall_order.sql"),
                    new ClassPathResource("db/mall/offline/V1__offline_payment.sql"),
                    new ClassPathResource("db/mall/event/V1__mall_events.sql"));
            schema.execute(mall.ds);
            mall.jdbc.execute("CREATE TABLE rollback_probe(id int)");
            Files.writeString(hostMigrations.resolve("V100__host.sql"), "CREATE TABLE host_marker(id INT);");
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(FlywayAutoConfiguration.class, CommerceMallDatabaseAutoConfiguration.class,
                            CommercePersistenceAutoConfiguration.class))
                    .withPropertyValues("commerce.enabled=true", "commerce.mall.enabled=true",
                            "shop.modules.mall.datasource=shopMall", "shop.modules.foundation.datasource=dictionary")
                    .withPropertyValues("spring.flyway.locations=filesystem:" + hostMigrations)
                    .withBean("dataSource", DataSource.class, () -> foundation.ds, bd -> bd.setPrimary(true))
                    .withBean("shopMallDataSource", DataSource.class, () -> mall.ds)
                    .withBean("shopMallTxManager", DataSourceTransactionManager.class, () -> new DataSourceTransactionManager(mall.ds))
                    .withBean("dictionaryDataSource", DataSource.class, () -> foundation.ds)
                    .withBean("platformDataSource", DataSource.class, () -> foundation.ds)
                    .withBean(CommerceIdGenerator.class, () -> ns -> "id")
                    .run(ctx -> {
                        assertNull(ctx.getStartupFailure());
                        assertFalse(ctx.containsBean("commerceUnitOfWork"));
                        assertEquals(0, foundation.jdbc.queryForObject(
                                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='commerce_schema_history'", Integer.class));
                        assertTrue(ctx.getBean(PurchaseOrderQueryPort.class).findByOrderNos("A", List.of("missing")).isEmpty());
                        var tx = ctx.getBean(CommerceUnitOfWork.class);
                        var events = ctx.getBean(CommerceEventPersistencePort.class);
                        var fact = new InboundFact("A","MALL_PAYMENT","receipt","PAID","order","digest",java.time.Instant.now());
                        assertThrows(IllegalStateException.class, () -> tx.required(() -> {
                            assertTrue(events.recordInbound(fact));
                            mall.jdbc.update("INSERT INTO rollback_probe VALUES (1)");
                            throw new IllegalStateException("abort");
                        }));
                        assertTrue(tx.required(() -> events.recordInbound(fact)));
                        assertFalse(tx.required(() -> events.recordInbound(fact)));
                        assertEquals(0, mall.jdbc.queryForObject("SELECT COUNT(*) FROM rollback_probe", Integer.class));
                    });
        }
    }
}

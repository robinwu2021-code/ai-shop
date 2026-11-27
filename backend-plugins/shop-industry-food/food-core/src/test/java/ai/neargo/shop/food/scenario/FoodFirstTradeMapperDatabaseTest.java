package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.auth.store.FreshAccountReadDataSource;
import ai.neargo.shop.common.AuditMetaObjectHandler;
import ai.neargo.shop.common.config.ModuleDataSourceRegistrar;
import ai.neargo.shop.common.config.ModuleDataSources;
import ai.neargo.shop.common.config.PrimaryPersistenceConfiguration;
import ai.neargo.shop.food.config.FoodPersistenceConfiguration;
import ai.neargo.shop.food.config.FoodProperties;
import ai.neargo.shop.food.entity.FnbChannelImportGuard;
import ai.neargo.shop.food.entity.FnbChannelOrderSource;
import ai.neargo.shop.food.entity.FnbOrderBatch;
import ai.neargo.shop.food.mapper.DiningChannelMappers.ChannelImportGuardMapper;
import ai.neargo.shop.food.mapper.DiningChannelMappers.ChannelOrderSourceMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OrderBatchMapper;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Proves mapper routing and rollback on the three legacy contracts using disposable MySQL. */
@EnabledIfEnvironmentVariable(named = "FOOD_TEST_DISPOSABLE", matches = "true")
class FoodFirstTradeMapperDatabaseTest {
    private static final Map<String, String> INITIAL_STATUSES = Map.of(
            "fnb_order_batch", "PREPARED",
            "fnb_channel_order_source", "REGISTERED",
            "fnb_channel_import_guard", "ACTIVE");
    private static final String HASH = "a".repeat(64);

    @Test
    void allThreeMappersWriteAndRollBackOnTradeWithPrimaryClosed() {
        String url = System.getenv("FOOD_TEST_JDBC_URL");
        assertThat(url).matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/(?:mysql|information_schema)");
        String user = System.getenv().getOrDefault("FOOD_TEST_DB_USER", "root");
        String password = System.getenv().getOrDefault("FOOD_TEST_DB_PASSWORD", "");
        String base = url.substring(0, url.lastIndexOf('/') + 1);
        String primary = "food_it_" + UUID.randomUUID().toString().replace("-", "");
        String trade = primary + "_trade";
        var admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        try {
            admin.execute("CREATE DATABASE " + primary);
            admin.execute("CREATE DATABASE " + trade);
            var primarySource = new DriverManagerDataSource(base + primary, user, password);
            var tradeSql = new JdbcTemplate(new DriverManagerDataSource(base + trade, user, password));
            new ResourceDatabasePopulator(new ClassPathResource("food-primary-fixture.sql"))
                    .execute(primarySource);
            Flyway.configure().dataSource(primarySource).table("fnb_flyway_history")
                    .locations("classpath:db/industry/food").baselineOnMigrate(true)
                    .baselineVersion("0").load().migrate();
            for (String table : INITIAL_STATUSES.keySet()) {
                tradeSql.execute("CREATE TABLE " + table + " LIKE " + primary + "." + table);
            }

            new ApplicationContextRunner()
                    .withUserConfiguration(PersistenceOnly.class)
                    .withBean(FoodProperties.class,
                            () -> new FoodProperties(true, FoodProperties.Deployment.STANDALONE))
                    .withBean(AuditMetaObjectHandler.class)
                    .withPropertyValues(
                            "shop.food.enabled=true",
                            "spring.datasource.url=" + base + primary,
                            "spring.datasource.username=" + user,
                            "spring.datasource.password=" + password,
                            "shop.modules.trade.datasource=foodRouteTest",
                            "shop.datasources.foodRouteTest.url=" + base + trade,
                            "shop.datasources.foodRouteTest.username=" + user,
                            "shop.datasources.foodRouteTest.password=" + password)
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        var primaryPool = context.getBean("dataSource", FreshAccountReadDataSource.class);
                        primaryPool.close();
                        assertThatThrownBy(primaryPool::getConnection).isInstanceOf(SQLException.class);

                        var batches = context.getBean(OrderBatchMapper.class);
                        var sources = context.getBean(ChannelOrderSourceMapper.class);
                        var guards = context.getBean(ChannelImportGuardMapper.class);
                        var transaction = new TransactionTemplate(context.getBean(
                                ModuleDataSources.TRADE_TX_MANAGER, PlatformTransactionManager.class));
                        var batch = batch();
                        var source = source();
                        var guard = guard();
                        transaction.executeWithoutResult(status -> {
                            assertThat(batches.insert(batch)).isOne();
                            assertThat(sources.insert(source)).isOne();
                            assertThat(guards.insert(guard)).isOne();
                        });
                        assertThat(batches.selectById(batch.getId()).getBatchNo()).isEqualTo("BATCH");
                        assertThat(sources.selectById(source.getId()).getExternalOrderKey()).isEqualTo("EXTERNAL");
                        assertThat(guards.selectById(guard.getId()).getImportNo()).isEqualTo("IMPORT");
                        assertCommittedStatuses(tradeSql);

                        transaction.executeWithoutResult(status -> {
                            batch.setStatus("CANCELLED");
                            source.setStatus("CANCELLED");
                            guard.setStatus("CANCELLED");
                            assertThat(batches.updateById(batch)).isOne();
                            assertThat(sources.updateById(source)).isOne();
                            assertThat(guards.updateById(guard)).isOne();
                            status.setRollbackOnly();
                        });
                        assertCommittedStatuses(tradeSql);
                    });
        } finally {
            admin.execute("DROP DATABASE IF EXISTS " + trade);
            admin.execute("DROP DATABASE IF EXISTS " + primary);
        }
    }

    private static void assertCommittedStatuses(JdbcTemplate trade) {
        INITIAL_STATUSES.forEach((table, expected) ->
                assertThat(trade.queryForObject("SELECT status FROM " + table, String.class))
                        .isEqualTo(expected));
    }

    private static FnbOrderBatch batch() {
        var batch = new FnbOrderBatch();
        batch.setEntityNo("ENTITY");
        batch.setStoreNo("STORE");
        batch.setBatchNo("BATCH");
        batch.setRequestKey("REQUEST");
        batch.setRequestHash(HASH);
        batch.setSource("CHANNEL");
        batch.setOperatorNo("SYSTEM");
        batch.setStatus("PREPARED");
        batch.setFrozenRequest("{}");
        batch.setCartNo("CART");
        batch.setCartVersion(0L);
        batch.setQuoteRef("QUOTE");
        batch.setQuoteHash(HASH);
        batch.setBusinessDate(LocalDate.of(2026, 11, 27));
        batch.setPolicyNo("POLICY");
        batch.setFulfillment("PICKUP");
        batch.setPayMode("CHANNEL");
        batch.setActorRealm("SYSTEM");
        batch.setQuotaClaimRefs("[]");
        return batch;
    }

    private static FnbChannelOrderSource source() {
        var source = new FnbChannelOrderSource();
        source.setEntityNo("ENTITY");
        source.setStoreNo("STORE");
        source.setSourceNo("SOURCE");
        source.setImportNo("IMPORT");
        source.setChannelStoreKey("CHANNEL_STORE");
        source.setExternalOrderKey("EXTERNAL");
        source.setExternalOrderRef("EXTERNAL_REF");
        source.setOriginalDocumentHash(HASH);
        source.setOriginalFinancialHash(HASH);
        source.setConnectionNo("CONNECTION");
        source.setConnectionRevisionNo("REVISION");
        source.setExternalSnapshotNo("SNAPSHOT");
        source.setPartyNo("PARTY");
        source.setCartNo("CART");
        source.setBatchNo("BATCH");
        source.setSourceVersion(0L);
        source.setSourceHash(HASH);
        source.setSourceSnapshot("{}");
        source.setEnvelopeSnapshot("{}");
        source.setStatus("REGISTERED");
        return source;
    }

    private static FnbChannelImportGuard guard() {
        var guard = new FnbChannelImportGuard();
        guard.setEntityNo("ENTITY");
        guard.setStoreNo("STORE");
        guard.setGuardNo("GUARD");
        guard.setImportNo("IMPORT");
        guard.setOriginalDocumentHash(HASH);
        guard.setStatus("ACTIVE");
        return guard;
    }

    @Configuration(proxyBeanMethods = false)
    @Import({PrimaryPersistenceConfiguration.class, ModuleDataSourceRegistrar.class,
            FoodPersistenceConfiguration.class})
    static class PersistenceOnly {}
}

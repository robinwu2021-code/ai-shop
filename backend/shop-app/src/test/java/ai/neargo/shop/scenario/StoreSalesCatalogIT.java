package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.event.OutboxConsumer;
import ai.neargo.shop.event.SysOutbox;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.common.AuditMetaObjectHandler;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.config.PrimaryPersistenceConfiguration;
import ai.neargo.shop.idem.IdempotencyService;
import ai.neargo.shop.idem.SysIdempotentMapper;
import ai.neargo.shop.spi.product.GoodsQueryPort;
import ai.neargo.shop.product.mapper.ProductMappers;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.nio.file.Files;
import org.flywaydb.core.Flyway;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.ObjectMapper;

/** Native storage/transaction acceptance; controlled quote/directory facts are not industry-provider acceptance. */
class StoreSalesCatalogIT {
    private String database;
    private DriverManagerDataSource source;
    private JdbcTemplate sql;
    private final ObjectMapper json = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-10-21T03:00:00Z");
    private static final String DEFINITION = "\"timezone\":\"Asia/Shanghai\",\"groups\":[{\"key\":\"FACE\","
            + "\"title\":\"面部护理\",\"position\":1,\"visible\":true}],\"entries\":[{\"skuNo\":\"SKU\","
            + "\"groupKey\":\"FACE\",\"position\":0,\"visible\":true,\"salesMode\":\"WINDOWED\","
            + "\"windows\":[{\"daysMask\":\"1111100\",\"startMin\":600,\"endMin\":720}]}]";

    private Class<?> type(String name) { return assertDoesNotThrow(() -> Class.forName(name)); }
    @BeforeEach
    void database() {
        type("ai.neargo.shop.product.service.impl.StoreSalesCatalogServiceImpl");
        String admin = System.getProperty("store-catalog.test.admin-url");
        assertThat(admin).isNotBlank();
        database = "store_catalog_" + UUID.randomUUID().toString().replace("-", "");
        var root = new DriverManagerDataSource(admin, "root", "");
        new JdbcTemplate(root).execute("CREATE DATABASE " + database
                + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        source = new DriverManagerDataSource(admin.replace("/mysql?", "/" + database + "?"), "root", "");
        Path repo = Path.of(System.getProperty("store-catalog.test.root"));
        new ResourceDatabasePopulator(new FileSystemResource(repo.resolve(
                "backend/shop-app/src/main/resources/db/migration/V596__shared_store_sales_catalog.sql")),
                new FileSystemResource(repo.resolve("backend-plugins/shop-industry-beauty/beauty-core/"
                        + "src/test/resources/platform-write-fixture.sql"))).execute(source);
        sql = new JdbcTemplate(source);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                LoginUser.merchant("ACTOR", "operator"), null, List.of()));
        BizContext.set(new BizContext("E", Set.of(), Set.of(), Set.of("S"), "S", true));
    }
    @AfterEach
    void clean() {
        BizContext.clear();
        SecurityContextHolder.clearContext();
        if (source != null) { sql.execute("DROP DATABASE " + database); }
    }
    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withUserConfiguration(PrimaryPersistenceConfiguration.class,
                type("ai.neargo.shop.product.service.impl.StoreSalesCatalogServiceImpl"), Mappers.class)
                .withBean(ObjectMapper.class, () -> json).withBean(AuditMetaObjectHandler.class)
                .withPropertyValues("spring.datasource.url=" + source.getUrl(), "spring.datasource.username=root",
                        "spring.datasource.password=", "shop.catalog.publication.archive-only=true");
    }
    private Object call(ApplicationContext context, String method, Object... args) {
        Object service = context.getBean(type("ai.neargo.shop.product.service.StoreSalesCatalogService"));
        var target = Arrays.stream(service.getClass().getMethods())
                .filter(m -> m.getName().equals(method) && m.getParameterCount() == args.length)
                .findFirst().orElseThrow();
        try { return target.invoke(service, args); }
        catch (InvocationTargetException ex) {
            if (ex.getCause() instanceof RuntimeException cause) { throw cause; }
            throw new AssertionError(ex.getCause());
        } catch (ReflectiveOperationException ex) { throw new AssertionError(ex); }
    }
    private Object publish(ApplicationContext context, long revision, String at, String definition, String key) {
        Object request = json.readValue("{\"expectedRevision\":" + revision + ",\"validFrom\":\"" + at + "\","
                + definition + "}", type("ai.neargo.shop.spi.product.StoreSalesCatalogPort$PublishReq"));
        return call(context, "publish", "E", "S", "ACTOR", request, key);
    }
    private String read(ApplicationContext context, Instant at) {
        return json.writeValueAsString(call(context, "read", "E", "S", at));
    }
    @Test
    void publishesImmutableScopedVersionAndReplaysOriginalResult() {
        runner().run(context -> {
            Object first = publish(context, 0, "2026-10-20T00:00:00Z", DEFINITION, "K");
            assertThat(json.writeValueAsString(publish(context, 0, "2026-10-20T00:00:00Z", DEFINITION, "K")))
                    .isEqualTo(json.writeValueAsString(first));
            assertThat(read(context, NOW)).contains("面部护理", "SKU", "WINDOWED");
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM prd_store_catalog_version", Integer.class)).isOne();
            assertThat(sql.queryForObject("SELECT status FROM sys_outbox", String.class)).isEqualTo("ARCHIVED");
        });
    }
    @Test
    void changedReplayAndStaleRevisionCannotOverwriteCurrentCatalog() {
        runner().run(context -> {
            publish(context, 0, "2026-10-20T00:00:00Z", DEFINITION, "K");
            assertThatThrownBy(() -> publish(context, 0, "2026-10-20T00:00:00Z",
                    DEFINITION.replace("面部护理", "changed"), "K")).isInstanceOf(BizException.class);
            assertThatThrownBy(() -> publish(context, 0, "2026-10-20T00:00:00Z", DEFINITION, "STALE"))
                    .isInstanceOf(BizException.class);
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM prd_store_catalog_version", Integer.class)).isOne();
        });
    }
    @Test
    void futureVersionDoesNotReplaceEffectiveHistory() {
        runner().run(context -> {
            publish(context, 0, "2026-10-20T00:00:00Z", DEFINITION, "K");
            publish(context, 1, "2026-10-22T00:00:00Z", DEFINITION.replace("面部护理", "future"), "NEXT");
            assertThat(read(context, NOW)).contains("面部护理").doesNotContain("future");
            assertThat(read(context, NOW.plusSeconds(86400))).contains("future");
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM prd_store_catalog_version", Integer.class))
                    .isEqualTo(2);
        });
    }
    @Test
    void missingAndForeignReferencesDoNotCreateCatalogs() {
        runner().run(context -> {
            assertThatThrownBy(() -> publish(context, 0, "2026-10-20T00:00:00Z",
                    DEFINITION.replace("SKU", "OTHER"), "FOREIGN")).isInstanceOf(BizException.class);
            assertThatThrownBy(() -> call(context, "read", "OTHER", "S", NOW)).isInstanceOf(BizException.class);
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM prd_store_catalog", Integer.class)).isZero();
        });
    }
    @Test
    void unconfiguredIsExplicitLegacyStateAndTamperedSnapshotFailsClosed() {
        runner().run(context -> {
            assertThat(read(context, NOW)).contains("\"configured\":false");
            publish(context, 0, "2026-10-20T00:00:00Z", DEFINITION, "K");
            sql.update("UPDATE prd_store_catalog_version SET frozen_payload=REPLACE(frozen_payload,?,?)",
                    "面部护理", "tampered");
            assertThatThrownBy(() -> call(context, "read", "E", "S", NOW)).isInstanceOf(BizException.class);
        });
    }
    @Test
    void committedReplaySurvivesOwnerOutageWhileNewPublicationStillFailsClosed() {
        runner().run(context -> {
            String original = json.writeValueAsString(publish(context, 0, "2026-10-20T00:00:00Z",
                    DEFINITION, "RECOVER"));
            var quotes = context.getBean(GoodsQueryPort.class);
            when(quotes.snapshot(List.of("SKU"), Map.of("E", "S")))
                    .thenThrow(new IllegalStateException("owner temporarily unavailable"));
            assertThat(json.writeValueAsString(assertDoesNotThrow(() -> publish(context, 0, "2026-10-20T00:00:00Z",
                    DEFINITION, "RECOVER"))))
                    .isEqualTo(original);
            assertThatThrownBy(() -> publish(context, 1, "2026-10-20T00:00:00Z", DEFINITION, "NEW-OUTAGE"))
                    .isInstanceOf(IllegalStateException.class).hasMessage("owner temporarily unavailable");
            doReturn(Map.of()).when(quotes).snapshot(List.of("SKU"), Map.of("E", "S"));
            assertThat(json.writeValueAsString(assertDoesNotThrow(() -> publish(context, 0, "2026-10-20T00:00:00Z",
                    DEFINITION, "RECOVER"))))
                    .isEqualTo(original);
            assertThatThrownBy(() -> publish(context, 1, "2026-10-20T00:00:00Z", DEFINITION, "NEW-OFFSHELF"))
                    .isInstanceOf(BizException.class);
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM prd_store_catalog_version", Integer.class)).isOne();
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM sys_idempotent", Integer.class)).isOne();
        });
    }
    @Test
    void incrementalFlywayUpgradePreservesExistingPlatformRowsAndRestartsCleanly() throws Exception {
        sql.execute("DROP TABLE prd_store_catalog_version");
        sql.execute("DROP TABLE prd_store_catalog");
        sql.execute("CREATE TABLE catalog_legacy_probe (reference_no VARCHAR(32) PRIMARY KEY)");
        sql.update("INSERT INTO catalog_legacy_probe VALUES('BEFORE_V536')");
        Path migrations = Files.createTempDirectory("store-catalog-v536-");
        Path migration = migrations.resolve("V596__shared_store_sales_catalog.sql");
        try {
            Files.copy(Path.of(System.getProperty("store-catalog.test.root"),
                    "backend/shop-app/src/main/resources/db/migration/V596__shared_store_sales_catalog.sql"),
                    migration);
            var flyway = Flyway.configure().dataSource(source).locations("filesystem:" + migrations)
                    .table("catalog_flyway_history").baselineOnMigrate(true).baselineVersion("535").load();
            assertThat(flyway.migrate().migrationsExecuted).isOne();
            flyway.validate();
            assertThat(flyway.migrate().migrationsExecuted).isZero();
            assertThat(sql.queryForObject("SELECT reference_no FROM catalog_legacy_probe", String.class))
                    .isEqualTo("BEFORE_V536");
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM catalog_flyway_history "
                    + "WHERE version='536' AND success=1", Integer.class)).isOne();
        } finally {
            Files.deleteIfExists(migration);
            Files.deleteIfExists(migrations);
        }
    }
    @Test
    void declaredConsumerConflictRollsBackHeadVersionAndIdempotency() {
        runner().withBean(OutboxConsumer.class, () -> new OutboxConsumer() {
            public boolean supports(String eventType) {
                return eventType.equals("PRODUCT_STORE_CATALOG_VERSION_PUBLISHED");
            }
            public void consume(SysOutbox event) { throw new AssertionError("archive must never deliver"); }
        }).run(context -> {
            assertThatThrownBy(() -> publish(context, 0, "2026-10-20T00:00:00Z", DEFINITION, "ROLLBACK"))
                    .isInstanceOf(IllegalStateException.class);
            for (String table : List.of("prd_store_catalog", "prd_store_catalog_version",
                    "sys_idempotent", "sys_outbox")) {
                assertThat(sql.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).isZero();
            }
        });
    }
    @Test
    void simultaneousFirstPublicationHasExactlyOneWinnerAndNoOrphans() {
        runner().run(context -> {
            var ready = new CountDownLatch(2);
            var go = new CountDownLatch(1);
            try (var pool = Executors.newFixedThreadPool(2)) {
                var work = IntStream.range(0, 2).mapToObj(index -> pool.submit(() -> {
                    ready.countDown();
                    assertThat(go.await(10, TimeUnit.SECONDS)).isTrue();
                    try {
                        publish(context, 0, "2026-10-20T00:00:00Z", DEFINITION, "RACE-" + index);
                        return true;
                    } catch (BizException conflict) { return false; }
                })).toList();
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                go.countDown();
                assertThat(work.get(0).get(20, TimeUnit.SECONDS) ^ work.get(1).get(20, TimeUnit.SECONDS)).isTrue();
            }
            for (String table : List.of("prd_store_catalog", "prd_store_catalog_version",
                    "sys_idempotent", "sys_outbox")) {
                assertThat(sql.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).isOne();
            }
        });
    }
    @Configuration(proxyBeanMethods = false)
    static class Mappers {
        private <T> T mapper(SqlSessionFactory factory, Class<T> type) {
            if (!factory.getConfiguration().hasMapper(type)) { factory.getConfiguration().addMapper(type); }
            return new SqlSessionTemplate(factory).getMapper(type);
        }
        @Bean
        ProductMappers.StoreCatalogMapper storeCatalogMapper(SqlSessionFactory factory) {
            return mapper(factory, ProductMappers.StoreCatalogMapper.class);
        }
        @Bean
        ProductMappers.StoreCatalogVersionMapper storeCatalogVersionMapper(SqlSessionFactory factory) {
            return mapper(factory, ProductMappers.StoreCatalogVersionMapper.class);
        }
        @Bean
        IdempotencyService idempotencyService(SqlSessionFactory factory, ObjectMapper json) {
            return new IdempotencyService(mapper(factory, SysIdempotentMapper.class), json);
        }
        @Bean
        StoreDirectoryPort stores() {
            var port = mock(StoreDirectoryPort.class);
            when(port.cards(List.of("S"))).thenReturn(Map.of("S", new StoreDirectoryPort.StoreCard(
                    "S", "store", "E", null, "ACTIVE", true, "09:00-18:00", "address", null, null, 0, 0)));
            return port;
        }
        @Bean
        GoodsQueryPort quotes() {
            var port = mock(GoodsQueryPort.class);
            when(port.snapshot(List.of("SKU"), Map.of("E", "S"))).thenReturn(Map.of("SKU",
                    new GoodsQueryPort.SkuSnapshot("SKU", "G", "E", "service", null, null, "SERVICE", null,
                            9900, 0, true, List.of("SERVICE"), null, null, "NORMAL", null, null, "S")));
            return port;
        }
    }
}

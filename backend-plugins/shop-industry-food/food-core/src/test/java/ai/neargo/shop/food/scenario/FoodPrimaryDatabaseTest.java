package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.*;

import ai.neargo.common.data.scope.*;
import ai.neargo.shop.auth.ScopeDim;
import ai.neargo.shop.common.AuditMetaObjectHandler;
import ai.neargo.shop.common.config.PrimaryPersistenceConfiguration;
import ai.neargo.shop.food.config.*;
import ai.neargo.shop.food.entity.FnbTableArea;
import ai.neargo.shop.food.mapper.FoodMappers;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Run only against the disposable container created by verify-food-primary-mysql.py. */
@EnabledIfEnvironmentVariable(named = "FOOD_TEST_DISPOSABLE", matches = "true")
class FoodPrimaryDatabaseTest {
    private String database;
    private DataSource source;
    private JdbcTemplate sql;
    private final FoodSchemaValidator validator = new FoodSchemaValidator();

    @BeforeEach void createDatabase() {
        String url = System.getenv("FOOD_TEST_JDBC_URL");
        assertThat(url).matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/mysql");
        database = "food_it_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(admin()).execute("CREATE DATABASE " + database);
        source = new DriverManagerDataSource(url.replace("/mysql", "/" + database), "root", "");
        sql = new JdbcTemplate(source);
        new ResourceDatabasePopulator(new ClassPathResource("food-primary-fixture.sql")).execute(source);
    }
    private DataSource admin() { return new DriverManagerDataSource(System.getenv("FOOD_TEST_JDBC_URL"), "root", ""); }
    @AfterEach void dropDatabase() {
        DataScopeContext.clear();
        if (database != null) new JdbcTemplate(admin()).execute("DROP DATABASE " + database);
    }
    /** Independent fixture applies the real immutable V1; does not use the validator to prepare validation tests. */
    private void appliedFood() {
        Flyway.configure().dataSource(source).table("fnb_flyway_history").locations("classpath:db/industry/food")
                .baselineOnMigrate(true).baselineVersion("0").load().migrate();
    }
    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(FoodAutoConfiguration.class))
                .withUserConfiguration(PrimaryPersistenceConfiguration.class)
                .withBean(AuditMetaObjectHandler.class)
                .withPropertyValues("shop.food.enabled=true", "spring.datasource.url=" + ((DriverManagerDataSource)source).getUrl(),
                        "spring.datasource.username=root", "spring.datasource.password=");
    }
    @Test void explicitPublishMigratesOnlyFood() {
        assertThat(validator.initialize(source, FoodProperties.MigrationMode.MIGRATE).ready()).isTrue();
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name LIKE 'fnb\\_%'", Integer.class)).isEqualTo(74);
        assertThat(tableExists("flyway_schema_history")).isFalse();
    }
    @Test void similarlyNamedTablesCannotSupplyMissingColumns() {
        appliedFood();
        sql.execute("ALTER TABLE sys_idempotent DROP COLUMN request_hash");
        sql.execute("CREATE TABLE sysXidempotent(request_hash VARCHAR(64))");
        assertThat(validator.initialize(source, FoodProperties.MigrationMode.VALIDATE).missingCapabilities())
                .contains("sys_idempotent.request_hash");
    }
    @Test void validateDoesNotApplyCoreMigrations() {
        appliedFood();
        sql.execute("ALTER TABLE sys_idempotent DROP COLUMN request_hash");
        var history = sql.queryForList("SELECT * FROM fnb_flyway_history ORDER BY installed_rank");
        var result = validator.initialize(source, FoodProperties.MigrationMode.VALIDATE);
        assertThat(result.validated()).isTrue();
        assertThat(result.ready()).isFalse();
        assertThat(result.missingCapabilities()).contains("sys_idempotent.request_hash");
        assertThat(sql.queryForList("SELECT * FROM fnb_flyway_history ORDER BY installed_rank")).isEqualTo(history);
        assertThat(tableExists("flyway_schema_history")).isFalse();
        assertThat(columnExists("sys_idempotent", "request_hash")).isFalse();
    }
    @Test void foodDoesNotSuppressCoreFlyway() {
        appliedFood();
        sql.execute("ALTER TABLE sys_idempotent DROP COLUMN request_hash");
        runner().withConfiguration(AutoConfigurations.of(FlywayAutoConfiguration.class))
                .withPropertyValues("spring.flyway.enabled=true", "spring.flyway.locations=classpath:food-test-core-migration",
                        "spring.flyway.baseline-on-migrate=true", "spring.flyway.baseline-version=0")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(Flyway.class);
                    assertThat(context.getBean(Flyway.class).getConfiguration().getTable()).isEqualTo("flyway_schema_history");
                    assertThat(context.getBean(FoodSchemaReady.class).ready()).isTrue();
                    assertThat(tableExists("core_migration_probe")).isTrue();
                });
    }
    @Test void bindFoodMapperToPlatformFactory() {
        appliedFood();
        runner().run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(DataSource.class).hasSingleBean(SqlSessionFactory.class)
                    .hasSingleBean(PlatformTransactionManager.class).doesNotHaveBean(Flyway.class);
            var factory = context.getBean(SqlSessionFactory.class);
            assertThat(context.getBean("foodSqlSessionTemplate", SqlSessionTemplate.class).getSqlSessionFactory()).isSameAs(factory);
            var mapper = context.getBean(FoodMappers.TableAreaMapper.class);
            var transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            assertThatThrownBy(() -> transaction.execute(status -> {
                mapper.insert(area("A", "rollback")); throw new IllegalStateException("rollback-probe");
            })).hasMessage("rollback-probe");
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM fnb_table_area", Integer.class)).isZero();
            var a = area("A", "A1"); var b = area("B", "B1");
            mapper.insert(a); mapper.insert(b);
            assertThat(a.getCreatedBy()).isEqualTo("SYSTEM");
            a = mapper.selectById(a.getId());
            assertThat(a.getTenantNo()).isEqualTo("MAIN");
            DataScopeContext.set(DataScopeSpec.of(ScopeDim.MERCHANT, java.util.Set.of("A")));
            assertThat(mapper.selectList(null)).extracting(FnbTableArea::getEntityNo).containsExactly("A");
            var stale = mapper.selectById(a.getId());
            a.setName("changed"); assertThat(mapper.updateById(a)).isOne();
            stale.setName("lost"); assertThat(mapper.updateById(stale)).isZero();
            b.setName("cross-scope"); assertThat(mapper.updateById(b)).isZero();
            assertThat(mapper.deleteById(a.getId())).isOne();
            assertThat(mapper.selectById(a.getId())).isNull();
        });
    }
    @Test void concurrentUpdatesHaveOneWinner() {
        appliedFood();
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            var mapper = context.getBean(FoodMappers.TableAreaMapper.class);
            var original = area("A", "race"); mapper.insert(original);
            var first = mapper.selectById(original.getId()); var second = mapper.selectById(original.getId());
            var start = new java.util.concurrent.CountDownLatch(1);
            try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
                var futures = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
                for (var row : java.util.List.of(first, second)) futures.add(pool.submit(() -> {
                    DataScopeContext.set(DataScopeSpec.of(ScopeDim.MERCHANT, java.util.Set.of("A")));
                    try {
                        if (!start.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("race timeout");
                        return new TransactionTemplate(context.getBean(PlatformTransactionManager.class)).execute(status -> {
                            row.setName("race-winner"); return mapper.updateById(row);
                        });
                    } finally { DataScopeContext.clear(); }
                }));
                start.countDown();
                assertThat(futures.get(0).get(10, java.util.concurrent.TimeUnit.SECONDS)
                        + futures.get(1).get(10, java.util.concurrent.TimeUnit.SECONDS)).isOne();
            }
            assertThat(mapper.selectById(original.getId()).getVersion()).isEqualTo(1);
        });
    }
    @Test void missingOutboxUniquenessBlocksReadiness() {
        appliedFood(); sql.execute("ALTER TABLE sys_outbox DROP INDEX uk_sys_outbox_source");
        assertThat(validator.initialize(source, FoodProperties.MigrationMode.VALIDATE).missingCapabilities())
                .contains("sys_outbox.unique(source_system,source_event_no)");
    }
    @Test void rejectUntrackedFoodTables() {
        sql.execute("CREATE TABLE fnb_table_area(id BIGINT)");
        assertThatThrownBy(() -> validator.initialize(source, FoodProperties.MigrationMode.MIGRATE)).hasMessageContaining("untracked");
        assertThat(tableExists("fnb_flyway_history")).isFalse();
    }
    @Test void validateNeverCreatesHistory() {
        assertThatThrownBy(() -> validator.initialize(source, FoodProperties.MigrationMode.VALIDATE)).hasMessageContaining("missing fnb_flyway_history");
        assertThat(tableExists("fnb_flyway_history")).isFalse();
    }
    @Test void rejectPendingHistory() {
        appliedFood(); sql.execute("DELETE FROM fnb_flyway_history WHERE version='1'");
        assertThatThrownBy(() -> validator.initialize(source, FoodProperties.MigrationMode.VALIDATE)).isInstanceOf(RuntimeException.class);
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM fnb_flyway_history WHERE version='1'", Integer.class)).isZero();
    }
    @Test void rejectChecksumDrift() {
        appliedFood(); sql.execute("UPDATE fnb_flyway_history SET checksum=1 WHERE version='1'");
        assertThatThrownBy(() -> validator.initialize(source, FoodProperties.MigrationMode.VALIDATE)).isInstanceOf(RuntimeException.class);
        assertThat(sql.queryForObject("SELECT checksum FROM fnb_flyway_history WHERE version='1'", Integer.class)).isOne();
    }
    @Test void rejectFailedHistory() {
        appliedFood(); sql.execute("UPDATE fnb_flyway_history SET success=0 WHERE version='1'");
        assertThatThrownBy(() -> validator.initialize(source, FoodProperties.MigrationMode.VALIDATE)).isInstanceOf(RuntimeException.class);
    }
    @Test void rejectUnknownFutureHistory() {
        appliedFood(); sql.execute("UPDATE fnb_flyway_history SET version='99' WHERE version='1'");
        assertThatThrownBy(() -> validator.initialize(source, FoodProperties.MigrationMode.VALIDATE)).isInstanceOf(RuntimeException.class);
    }
    @Test void rejectPhysicalColumnDrift() {
        appliedFood(); sql.execute("ALTER TABLE fnb_table_area DROP COLUMN name");
        assertThatThrownBy(() -> validator.initialize(source, FoodProperties.MigrationMode.VALIDATE)).hasMessageContaining("fnb_table_area.name");
    }
    @Test void missingCoreCapabilitiesBlockMappers() {
        appliedFood(); sql.execute("DROP TABLE sys_outbox_delivery");
        var ready = validator.initialize(source, FoodProperties.MigrationMode.VALIDATE);
        assertThat(ready.validated()).isTrue(); assertThat(ready.ready()).isFalse();
        assertThat(ready.missingCapabilities()).contains("sys_outbox_delivery");
        runner().run(context -> assertThat(context).hasFailed());
        assertThat(tableExists("sys_outbox_delivery")).isFalse();
    }
    private boolean tableExists(String name) {
        return sql.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=?", Integer.class, name)>0;
    }
    private boolean columnExists(String table, String field) {
        return sql.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=? AND column_name=?", Integer.class, table, field)>0;
    }
    private FnbTableArea area(String entity, String code) {
        var area = new FnbTableArea(); area.setEntityNo(entity); area.setAreaNo(code); area.setStoreNo("S"+entity);
        area.setCode(code); area.setName(code); area.setStatus("ACTIVE"); area.setSortNo(1); return area;
    }
}

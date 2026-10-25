package ai.neargo.shop.report.config;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import java.util.Map;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportStorageInitializerTest {

    @Test
    void isolatedConstructionDoesNotConnectAndBlocksBothConnectionPaths() {
        ReportStoreProperties props = isolated();
        props.setInitializationRole(ReportStoreProperties.InitializationRole.MIGRATOR);
        try (HikariDataSource ds = database()) {
            ds.setJdbcUrl("jdbc:h2:mem:unavailable;IFEXISTS=TRUE");
            var receipt = new ReportDataSourceConfig().reportFlyway(ds, props);
            var initializer = receipt.initializer();
            assertThat(initializer.state()).isEqualTo(ReportStorageInitializer.State.NOT_READY);
            assertThat(ds.getHikariPoolMXBean()).isNull();
            assertThatThrownBy(() -> initializer.guardedDataSource().getConnection())
                    .isInstanceOf(SQLException.class).hasMessage("REPORT_STORAGE_NOT_READY");
            assertThatThrownBy(() -> initializer.guardedDataSource().getConnection("sa", ""))
                    .isInstanceOf(SQLException.class).hasMessage("REPORT_PER_CALL_CREDENTIALS_FORBIDDEN");
            assertThat(ds.getHikariPoolMXBean()).isNull();
        }
    }

    @Test
    void isolatedSpringContextStartsWithoutDatabaseAndUsesGuardedTransactionResource() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("isolated", Map.of(
                    "shop.report.enabled", "true",
                    "shop.report.initialization-mode", "ISOLATED",
                    "shop.report.datasource.url", "jdbc:h2:mem:missing_report;IFEXISTS=TRUE")));
            context.register(ReportDataSourceConfig.class);
            context.refresh();
            var ds = context.getBean("reportDataSource", HikariDataSource.class);
            var initializer = context.getBean(ReportStorageInitializer.class);
            assertThat(initializer.state()).isEqualTo(ReportStorageInitializer.State.NOT_READY);
            assertThat(ds.getHikariPoolMXBean()).isNull();
            assertThat(context.getBean("reportTransactionManager", DataSourceTransactionManager.class)
                    .getDataSource()).isSameAs(initializer.guardedDataSource());
        }
    }

    @Test
    void validatorNeverMigratesAndCanRecoverAfterWorkerMigration() throws Exception {
        ReportStoreProperties validator = isolated();
        try (HikariDataSource ds = database()) {
            var reader = initializer(ds, validator);
            assertThatThrownBy(reader::initialize).isInstanceOf(RuntimeException.class);
            assertThat(reader.state()).isEqualTo(ReportStorageInitializer.State.FAILED);
            try (Connection c = ds.getConnection()) {
                assertThat(c.getMetaData().getTables(null, null, "RPT_INIT_PROBE", null).next())
                        .isFalse();
            }
            ReportStoreProperties worker = isolated();
            worker.setInitializationRole(ReportStoreProperties.InitializationRole.MIGRATOR);
            var writer = initializer(ds, worker);
            writer.initialize();
            assertThat(JdbcClient.create(reader.guardedDataSource())
                    .sql("SELECT COUNT(*) FROM rpt_init_probe").query(Long.class).single()).isZero();
            assertThat(reader.state()).isEqualTo(ReportStorageInitializer.State.READY);
            assertThat(reader.failureCode()).isNull();
            reader.initialize();
            assertThat(JdbcClient.create(reader.guardedDataSource())
                    .sql("SELECT COUNT(*) FROM rpt_init_probe").query(Long.class).single()).isZero();
        }
    }

    @Test
    void disabledFlywayIsNotAnIsolatedReadinessProof() {
        ReportStoreProperties props = isolated();
        props.setFlywayEnabled(false);
        props.setInitializationRole(ReportStoreProperties.InitializationRole.MIGRATOR);
        try (HikariDataSource ds = database()) {
            var initializer = initializer(ds, props);
            assertThatThrownBy(initializer::initialize).isInstanceOf(RuntimeException.class);
            assertThat(initializer.state()).isEqualTo(ReportStorageInitializer.State.FAILED);
        }
    }

    @Test
    void guardedJdbcAndTransactionManagerShareSameConnectionResource() {
        ReportStoreProperties props = isolated();
        props.setInitializationRole(ReportStoreProperties.InitializationRole.MIGRATOR);
        try (HikariDataSource ds = database()) {
            var initializer = initializer(ds, props);
            initializer.initialize();
            assertThat(initializer.guardedDataSource()).isSameAs(initializer.guardedDataSource());
            JdbcClient jdbc = JdbcClient.create(initializer.guardedDataSource());
            TransactionTemplate tx = new TransactionTemplate(
                    new DataSourceTransactionManager(initializer.guardedDataSource()));
            tx.executeWithoutResult(status -> {
                jdbc.sql("INSERT INTO rpt_init_probe(id) VALUES(1)").update();
                status.setRollbackOnly();
            });
            assertThat(jdbc.sql("SELECT COUNT(*) FROM rpt_init_probe")
                    .query(Long.class).single()).isZero();
        }
    }

    @Test void runtimeSchemaFailureInvalidatesReadinessAndValidatorCanRecoverAfterRepair() {
        var worker=isolated();worker.setInitializationRole(ReportStoreProperties.InitializationRole.MIGRATOR);
        try(var ds=database()) {
            initializer(ds,worker).initialize();var reader=initializer(ds,isolated());reader.initialize();
            var jdbc=JdbcClient.create(reader.guardedDataSource());
            assertThatThrownBy(()->jdbc.sql("SELECT COUNT(*) FROM rpt_late_probe").query(Long.class).single()).isInstanceOf(RuntimeException.class);
            assertThat(reader.state()).isEqualTo(ReportStorageInitializer.State.FAILED);
            assertThat(reader.failureKind()).isEqualTo(ReportStorageInitializer.FailureKind.SCHEMA_NOT_READY);
            JdbcClient.create(ds).sql("CREATE TABLE rpt_late_probe(id INT)").update();
            assertThat(jdbc.sql("SELECT COUNT(*) FROM rpt_late_probe").query(Long.class).single()).isZero();
            assertThat(reader.state()).isEqualTo(ReportStorageInitializer.State.READY);
        }
    }

    private static ReportStoreProperties isolated() {
        ReportStoreProperties props = new ReportStoreProperties();
        props.setInitializationMode(ReportStoreProperties.InitializationMode.ISOLATED);
        props.setRetryDelayMillis(0);
        props.setFlywayLocations("classpath:db/report-init-test");
        return props;
    }

    private static ReportStorageInitializer initializer(HikariDataSource ds, ReportStoreProperties props) {
        try (Connection c = ds.getConnection()) {
            props.setExpectedCatalog(c.getCatalog());
            props.setExpectedInstance(c.getMetaData().getURL());
            props.getMigrationDatasource().setUrl(ds.getJdbcUrl());
            props.getMigrationDatasource().setUsername(ds.getUsername());
            props.getMigrationDatasource().setPassword(ds.getPassword());
            return new ReportStorageInitializer(ds, props);
        } catch (SQLException ex) { throw new IllegalStateException(ex); }
    }

    private static HikariDataSource database() {
        HikariDataSource ds = new HikariDataSource();
        ds.setPoolName("rpt-pool");
        ds.setJdbcUrl("jdbc:h2:mem:init_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        ds.setUsername("sa");
        ds.setPassword("");
        return ds;
    }
}

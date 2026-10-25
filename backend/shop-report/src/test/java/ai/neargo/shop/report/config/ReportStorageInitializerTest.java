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
                    .isInstanceOf(SQLException.class).hasMessage("REPORT_STORAGE_NOT_READY");
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
            var reader = new ReportStorageInitializer(ds, validator);
            assertThatThrownBy(reader::initialize).isInstanceOf(RuntimeException.class);
            assertThat(reader.state()).isEqualTo(ReportStorageInitializer.State.FAILED);
            try (Connection c = ds.getConnection()) {
                assertThat(c.getMetaData().getTables(null, null, "REPORT_INIT_PROBE", null).next())
                        .isFalse();
            }
            ReportStoreProperties worker = isolated();
            worker.setInitializationRole(ReportStoreProperties.InitializationRole.MIGRATOR);
            var writer = new ReportStorageInitializer(ds, worker);
            writer.initialize();
            assertThat(JdbcClient.create(reader.guardedDataSource())
                    .sql("SELECT COUNT(*) FROM report_init_probe").query(Long.class).single()).isZero();
            assertThat(reader.state()).isEqualTo(ReportStorageInitializer.State.READY);
            assertThat(reader.failureCode()).isNull();
            reader.initialize();
            assertThat(JdbcClient.create(reader.guardedDataSource())
                    .sql("SELECT COUNT(*) FROM report_init_probe").query(Long.class).single()).isZero();
        }
    }

    @Test
    void disabledFlywayIsNotAnIsolatedReadinessProof() {
        ReportStoreProperties props = isolated();
        props.setFlywayEnabled(false);
        props.setInitializationRole(ReportStoreProperties.InitializationRole.MIGRATOR);
        try (HikariDataSource ds = database()) {
            var initializer = new ReportStorageInitializer(ds, props);
            assertThatThrownBy(initializer::initialize).isInstanceOf(RuntimeException.class);
            assertThat(initializer.state()).isEqualTo(ReportStorageInitializer.State.FAILED);
        }
    }

    @Test
    void guardedJdbcAndTransactionManagerShareSameConnectionResource() {
        ReportStoreProperties props = isolated();
        props.setInitializationRole(ReportStoreProperties.InitializationRole.MIGRATOR);
        try (HikariDataSource ds = database()) {
            var initializer = new ReportStorageInitializer(ds, props);
            initializer.initialize();
            assertThat(initializer.guardedDataSource()).isSameAs(initializer.guardedDataSource());
            JdbcClient jdbc = JdbcClient.create(initializer.guardedDataSource());
            TransactionTemplate tx = new TransactionTemplate(
                    new DataSourceTransactionManager(initializer.guardedDataSource()));
            tx.executeWithoutResult(status -> {
                jdbc.sql("INSERT INTO report_init_probe(id) VALUES(1)").update();
                status.setRollbackOnly();
            });
            assertThat(jdbc.sql("SELECT COUNT(*) FROM report_init_probe")
                    .query(Long.class).single()).isZero();
        }
    }

    private static ReportStoreProperties isolated() {
        ReportStoreProperties props = new ReportStoreProperties();
        props.setInitializationMode(ReportStoreProperties.InitializationMode.ISOLATED);
        props.setFlywayLocations("classpath:db/report-init-test");
        return props;
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

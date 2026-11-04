package ai.neargo.commerce.starter;

import static org.junit.jupiter.api.Assertions.*;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.*;

import javax.sql.DataSource;

class CommerceMigrationStartupTest {
    @TempDir Path host;

    Class<?> commerce() {
        return CommercePersistenceAutoConfiguration.class;
    }

    @Test
    void bootHostFlywayStaysEnabledAndCommerceRunsBeforeActivation() throws Exception {
        try (var db = new Database()) {
            Files.writeString(host.resolve("V100__host.sql"), "CREATE TABLE host_marker(id INT);");
            var runner =
                    new ApplicationContextRunner()
                            .withConfiguration(
                                    AutoConfigurations.of(
                                            FlywayAutoConfiguration.class, commerce()))
                            .withUserConfiguration(Activation.class)
                            .withBean(DataSource.class, () -> db.ds)
                            .withBean(
                                    org.springframework.transaction.PlatformTransactionManager
                                            .class,
                                    () ->
                                            new org.springframework.jdbc.datasource
                                                    .DataSourceTransactionManager(db.ds))
                            .withPropertyValues(
                                    "commerce.enabled=true",
                                    "spring.flyway.locations=filesystem:" + host);
            runner.run(
                    ctx -> {
                        assertNull(ctx.getStartupFailure());
                        assertEquals(1, ctx.getBeansOfType(Flyway.class).size());
                        assertEquals(
                                "flyway_schema_history",
                                ctx.getBean(Flyway.class).getConfiguration().getTable());
                        assertNotNull(ctx.getBean("activated"));
                    });
            Files.writeString(
                    host.resolve("V101__host_later.sql"),
                    "ALTER TABLE host_marker ADD later_flag INT;");
            runner.run(
                    ctx -> {
                        assertNull(ctx.getStartupFailure());
                        assertEquals(1, ctx.getBeansOfType(Flyway.class).size());
                        assertEquals(
                                "101",
                                db.jdbc.queryForObject(
                                        "SELECT version FROM flyway_schema_history ORDER BY"
                                                + " installed_rank DESC LIMIT 1",
                                        String.class));
                    });
        }
    }

    @Test
    void failingCommerceMigrationBlocksDownstreamInitialization() throws Exception {
        try (var db = new Database()) {
            Files.writeString(
                    host.resolve("V100__host.sql"),
                    "CREATE TABLE sys_business_type(id INT); CREATE TABLE host_marker(id INT);");
            new ApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(FlywayAutoConfiguration.class, commerce()))
                    .withUserConfiguration(Activation.class)
                    .withBean(DataSource.class, () -> db.ds)
                    .withBean(
                            org.springframework.transaction.PlatformTransactionManager.class,
                            () ->
                                    new org.springframework.jdbc.datasource
                                            .DataSourceTransactionManager(db.ds))
                    .withPropertyValues(
                            "commerce.enabled=true", "spring.flyway.locations=filesystem:" + host)
                    .run(
                            ctx -> {
                                assertNotNull(ctx.getStartupFailure());
                                assertTrue(
                                        ctx.getStartupFailure()
                                                .getMessage()
                                                .contains("commerceMigrationInitializer"));
                                assertEquals(
                                        0,
                                        db.jdbc.queryForObject(
                                                "SELECT COUNT(*) FROM information_schema.tables"
                                                        + " WHERE table_schema=? AND"
                                                        + " table_name='activation_marker'",
                                                Integer.class,
                                                db.name));
                                assertEquals(
                                        1,
                                        db.jdbc.queryForObject(
                                                "SELECT COUNT(*) FROM flyway_schema_history WHERE"
                                                        + " success=1",
                                                Integer.class));
                            });
        }
    }

    @Test
    void disabledModuleDoesNotCreateCommerceSchema() throws Exception {
        try (var db = new Database()) {
            Files.writeString(host.resolve("V100__host.sql"), "CREATE TABLE host_marker(id INT);");
            new ApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(FlywayAutoConfiguration.class, commerce()))
                    .withBean(DataSource.class, () -> db.ds)
                    .withBean(
                            org.springframework.transaction.PlatformTransactionManager.class,
                            () ->
                                    new org.springframework.jdbc.datasource
                                            .DataSourceTransactionManager(db.ds))
                    .withPropertyValues("spring.flyway.locations=filesystem:" + host)
                    .run(
                            ctx -> {
                                assertNull(ctx.getStartupFailure());
                                assertFalse(ctx.containsBean("commerceMigrationInitializer"));
                                assertEquals(
                                        0,
                                        db.jdbc.queryForObject(
                                                "SELECT COUNT(*) FROM information_schema.tables"
                                                        + " WHERE table_schema=? AND"
                                                        + " table_name='commerce_schema_history'",
                                                Integer.class,
                                                db.name));
                            });
        }
    }

    @Test
    void explicitEventAdapterReplacesDefaultWriterWithoutDuplicateBean() throws Exception {
        try (var db = new Database()) {
            Files.writeString(host.resolve("V100__host.sql"), "CREATE TABLE host_marker(id INT);");
            var external = new ai.neargo.commerce.persistence.JdbcCommerceEventPersistence(db.ds);
            new ApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(FlywayAutoConfiguration.class, commerce()))
                    .withBean(DataSource.class, () -> db.ds)
                    .withBean(
                            org.springframework.transaction.PlatformTransactionManager.class,
                            () ->
                                    new org.springframework.jdbc.datasource
                                            .DataSourceTransactionManager(db.ds))
                    .withBean(
                            ai.neargo.commerce.api.port.CommerceEventPersistencePort.class,
                            () -> external)
                    .withPropertyValues(
                            "commerce.enabled=true", "spring.flyway.locations=filesystem:" + host)
                    .run(
                            ctx -> {
                                assertNull(ctx.getStartupFailure());
                                assertEquals(
                                        1,
                                        ctx.getBeansOfType(
                                                        ai.neargo.commerce.api.port
                                                                .CommerceEventPersistencePort.class)
                                                .size());
                                assertSame(
                                        external,
                                        ctx.getBean(
                                                ai.neargo.commerce.api.port
                                                        .CommerceEventPersistencePort.class));
                                assertFalse(ctx.containsBean("commerceEventPersistence"));
                            });
        }
    }

    @Test
    void missingHostInitializerFailsClosedBeforeCommerceMigration() {
        try (var db = new Database()) {
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(commerce()))
                    .withBean(DataSource.class, () -> db.ds)
                    .withBean(
                            org.springframework.transaction.PlatformTransactionManager.class,
                            () ->
                                    new org.springframework.jdbc.datasource
                                            .DataSourceTransactionManager(db.ds))
                    .withPropertyValues("commerce.enabled=true")
                    .run(
                            ctx -> {
                                assertNotNull(ctx.getStartupFailure());
                                assertEquals(
                                        0,
                                        db.jdbc.queryForObject(
                                                "SELECT COUNT(*) FROM information_schema.tables"
                                                    + " WHERE table_schema=?",
                                                Integer.class,
                                                db.name));
                            });
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Activation {
        @Bean
        @DependsOn("commerceMigrationInitializer")
        InitializingBean activated(DataSource ds) {
            return () -> {
                var jdbc = new JdbcTemplate(ds);
                assertEquals(
                        1,
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM information_schema.tables WHERE"
                                        + " table_schema=DATABASE() AND table_name='host_marker'",
                                Integer.class));
                assertEquals(
                        1,
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM information_schema.tables WHERE"
                                        + " table_schema=DATABASE() AND"
                                        + " table_name='mch_subscription_account'",
                                Integer.class));
                jdbc.execute("CREATE TABLE IF NOT EXISTS activation_marker(id INT)");
            };
        }
    }

    static final class Database implements AutoCloseable {
        final String name =
                "commerce_test_" + java.util.UUID.randomUUID().toString().replace("-", "");
        final DataSource admin, ds;
        final JdbcTemplate jdbc;

        Database() {
            String url = required("COMMERCE_MYSQL_URL"),
                    u = required("COMMERCE_MYSQL_USER"),
                    p = required("COMMERCE_MYSQL_PASSWORD");
            admin = new DriverManagerDataSource(url, u, p);
            new JdbcTemplate(admin).execute("CREATE DATABASE " + name);
            ds = new DriverManagerDataSource(url.replace("?", name + "?"), u, p);
            jdbc = new JdbcTemplate(ds);
        }

        static String required(String k) {
            String s = System.getenv(k);
            if (s == null || s.isBlank())
                throw new IllegalStateException("Real MySQL required: " + k);
            return s;
        }

        public void close() {
            new JdbcTemplate(admin).execute("DROP DATABASE " + name);
        }
    }
}

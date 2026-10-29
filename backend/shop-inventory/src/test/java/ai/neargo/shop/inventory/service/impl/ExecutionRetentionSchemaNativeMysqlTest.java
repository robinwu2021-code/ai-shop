package ai.neargo.shop.inventory.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.inventory.policy.ExecutionStockRetentionSchemaVerifier.Installation;
import ai.neargo.shop.inventory.policy.ExecutionStockRetentionSchemaVerifier;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Native physical installation fixture: actual V1..V17 migration, never an inserted history marker or H2 shell. */
@EnabledIfSystemProperty(named = "inventory.lock.test.native", matches = "true")
class ExecutionRetentionSchemaNativeMysqlTest {
    @TempDir Path resources;
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private String catalog;
    private DataSource isolated;
    private ExecutionStockRetentionSchemaVerifier verifier;

    @BeforeEach
    void createAnEmptyNativeCatalog() {
        var source = new DriverManagerDataSource(System.getProperty("inventory.lock.test.url"),
                System.getProperty("inventory.lock.test.user", "root"),
                System.getProperty("inventory.lock.test.password", ""));
        admin = new JdbcTemplate(source);
        assertThat(admin.queryForObject("SELECT VERSION()", String.class)).doesNotContainIgnoringCase("h2");
        catalog = "c04ret" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE DATABASE `" + catalog + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci");
        isolated = new AbstractDataSource() {
            @Override
            public Connection getConnection() throws SQLException {
                return select(source.getConnection());
            }

            @Override
            public Connection getConnection(String username, String password) throws SQLException {
                return select(source.getConnection(username, password));
            }

            private Connection select(Connection connection) throws SQLException {
                try {
                    connection.setCatalog(catalog);
                    return connection;
                } catch (SQLException failure) {
                    connection.close();
                    throw failure;
                }
            }
        };
        jdbc = new JdbcTemplate(isolated);
        verifier = new ExecutionStockRetentionSchemaVerifier(isolated, "inv_flyway_history", null);
    }

    @AfterEach
    void dropOnlyThisFixtureCatalog() {
        if (admin != null && catalog != null) {
            admin.execute("DROP DATABASE `" + catalog + "`");
        }
    }

    private void migrate(String target) {
        Flyway.configure().dataSource(isolated).locations("classpath:db/inventory").table("inv_flyway_history")
                .target(target).load().migrate();
    }

    @Test
    void genuineV16AbsenceThenForwardV17IsInstalledWithoutBackfilledQuantity() {
        assertThat(verifier.installation()).isEqualTo(Installation.ABSENT);
        migrate("16");
        assertThat(verifier.installation()).isEqualTo(Installation.ABSENT);
        migrate("17");
        assertThat(verifier.installation()).isEqualTo(Installation.INSTALLED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inv_execution_retention", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inv_execution_retention_claim", Long.class)).isZero();
    }

    @Test
    void partialTableWithoutAnAppliedMarkerIsCorruptRatherThanLegacyAbsence() {
        jdbc.execute("CREATE TABLE inv_execution_retention(id BIGINT PRIMARY KEY) ENGINE=InnoDB");
        assertThat(verifier.installation()).isEqualTo(Installation.CORRUPT);
    }

    @Test
    void validMarkerCannotMaskMissingPermanentHistoryTrigger() {
        migrate("17");
        assertThat(verifier.installation()).isEqualTo(Installation.INSTALLED);
        jdbc.execute("DROP TRIGGER trg_ierc_no_delete");
        assertThat(verifier.installation()).isEqualTo(Installation.CORRUPT);
    }

    @Test
    void anEarlierInstalledObservationDoesNotCacheAwayLaterMissingOwnerTables() {
        migrate("17");
        assertThat(verifier.installation()).isEqualTo(Installation.INSTALLED);
        jdbc.execute("DROP TABLE inv_execution_retention");
        assertThat(verifier.installation()).isEqualTo(Installation.CORRUPT);
    }
    @Test
    void explicitNativeOwnerCollationSurvivesADifferentDatabaseDefault() {
        migrate("17");
        assertThat(jdbc.queryForObject("SELECT DEFAULT_COLLATION_NAME FROM information_schema.SCHEMATA"
                + " WHERE SCHEMA_NAME=?", String.class, catalog)).isEqualTo("utf8mb4_general_ci");
        String owner = jdbc.queryForObject("SELECT COLLATION_NAME FROM information_schema.COLUMNS"
                + " WHERE TABLE_SCHEMA=? AND TABLE_NAME='inv_owner' AND COLUMN_NAME='owner_id'",
                String.class, catalog);
        assertThat(owner).isNotEqualTo("utf8mb4_general_ci");
        assertThat(verifier.installation()).isEqualTo(Installation.INSTALLED);
        for (String table : List.of("inv_execution_retention", "inv_execution_retention_claim")) {
            assertThat(jdbc.queryForObject("SELECT TABLE_COLLATION FROM information_schema.TABLES"
                    + " WHERE TABLE_SCHEMA=? AND TABLE_NAME=?", String.class, catalog, table)).isEqualTo(owner);
        }
        assertThat(jdbc.queryForObject("SELECT COLLATION_NAME FROM information_schema.COLUMNS"
                + " WHERE TABLE_SCHEMA=? AND TABLE_NAME='inv_execution_retention' AND COLUMN_NAME='owner_id'",
                String.class, catalog)).isEqualTo(owner);
    }

    @ParameterizedTest
    @ValueSource(strings = {"COLUMN", "INDEX", "CHECK"})
    void actualNativeColumnIndexAndCheckDriftAreIndependentlyRejected(String kind) {
        migrate("17");
        assertThat(verifier.installation()).isEqualTo(Installation.INSTALLED);
        switch (kind) {
            case "COLUMN" -> jdbc.execute("ALTER TABLE inv_execution_retention"
                    + " MODIFY component_no VARCHAR(63) COLLATE utf8mb4_bin NOT NULL");
            case "INDEX" -> jdbc.execute("ALTER TABLE inv_execution_retention DROP INDEX idx_ier_component");
            case "CHECK" -> {
                String version = jdbc.queryForObject("SELECT VERSION()", String.class);
                String drop = version.toLowerCase(Locale.ROOT).contains("mariadb")
                        ? " DROP CONSTRAINT " : " DROP CHECK ";
                jdbc.execute("ALTER TABLE inv_execution_retention" + drop + "ck_ier_positive");
            }
            default -> throw new IllegalArgumentException(kind);
        }
        assertThat(verifier.installation()).isEqualTo(Installation.CORRUPT);
    }

    @Test
    void explicitWrongCatalogCannotAttestAnotherOwnersInstallation() {
        migrate("17");
        var wrong = new ExecutionStockRetentionSchemaVerifier(isolated, "inv_flyway_history", catalog + "wrong");
        assertThat(wrong.installation()).isEqualTo(Installation.CORRUPT);
    }

    @Test
    void anUnenforcedNativeCheckCannotClaimInstalledWhenTheServerExposesEnforcement() {
        migrate("17");
        var columns = jdbc.queryForList("SELECT COLUMN_NAME FROM information_schema.COLUMNS"
                + " WHERE TABLE_SCHEMA='information_schema' AND TABLE_NAME='TABLE_CONSTRAINTS'"
                + " AND COLUMN_NAME='ENFORCED'");
        Assumptions.assumeFalse(columns.isEmpty(), "Server does not expose ENFORCED metadata");
        jdbc.execute("ALTER TABLE inv_execution_retention ALTER CHECK ck_ier_positive NOT ENFORCED");
        assertThat(verifier.installation()).isEqualTo(Installation.CORRUPT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DESCRIPTOR_BYTES", "MIGRATION_BYTES", "MISSING_DESCRIPTOR", "DUPLICATE_DESCRIPTOR"})
    void actualNativeInstallationCannotBeAttestedByWrongOrAmbiguousPackagedResources(String kind) throws Exception {
        migrate("17");
        assertThat(verifier.installation()).isEqualTo(Installation.INSTALLED);
        String descriptor = "META-INF/shop/inventory-execution-retention-schema.json";
        String migration = "db/inventory/V17__execution_stock_retention.sql";
        String target = "MIGRATION_BYTES".equals(kind) ? migration : descriptor;
        var urls = new ArrayList<URL>();
        if (!"MISSING_DESCRIPTOR".equals(kind)) {
            Path changed = resources.resolve("changed-source");
            if ("DESCRIPTOR_BYTES".equals(kind)) {
                Files.writeString(changed, "{}");
            } else {
                try (InputStream stream = getClass().getClassLoader().getResourceAsStream(target)) {
                    Files.write(changed, stream.readAllBytes());
                }
                if ("MIGRATION_BYTES".equals(kind)) {
                    Files.writeString(changed, "\n-- different migration resource bytes\n",
                            StandardOpenOption.APPEND);
                }
            }
            urls.add(changed.toUri().toURL());
            if ("DUPLICATE_DESCRIPTOR".equals(kind)) {
                urls.add(changed.toUri().toURL());
            }
        }
        assertThat(installationWithResources(target, urls)).isEqualTo("CORRUPT");
    }

    private String installationWithResources(String target, List<URL> replacements) throws Exception {
        String verifierName = ExecutionStockRetentionSchemaVerifier.class.getName();
        ClassLoader parent = ExecutionStockRetentionSchemaVerifier.class.getClassLoader();
        ClassLoader loader = new ClassLoader(parent) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.equals(verifierName) && !name.startsWith(verifierName + "$")) {
                    return super.loadClass(name, resolve);
                }
                synchronized (getClassLoadingLock(name)) {
                    Class<?> type = findLoadedClass(name);
                    if (type == null) {
                        try (InputStream stream = parent.getResourceAsStream(name.replace('.', '/') + ".class")) {
                            if (stream == null) {
                                throw new ClassNotFoundException(name);
                            }
                            byte[] bytes = stream.readAllBytes();
                            type = defineClass(name, bytes, 0, bytes.length);
                        } catch (IOException failure) {
                            throw new ClassNotFoundException(name, failure);
                        }
                    }
                    if (resolve) {
                        resolveClass(type);
                    }
                    return type;
                }
            }

            @Override
            public Enumeration<URL> getResources(String name) throws IOException {
                return target.equals(name) ? Collections.enumeration(replacements) : super.getResources(name);
            }
        };
        var type = Class.forName(verifierName, true, loader);
        Object isolatedVerifier = type.getConstructor(DataSource.class, String.class, String.class)
                .newInstance(isolated, "inv_flyway_history", null);
        return type.getMethod("installation").invoke(isolatedVerifier).toString();
    }

}

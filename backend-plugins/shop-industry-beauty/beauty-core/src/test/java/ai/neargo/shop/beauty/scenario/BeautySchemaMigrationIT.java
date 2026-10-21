package ai.neargo.shop.beauty.scenario;

import com.mysql.cj.jdbc.MysqlDataSource;
import java.lang.reflect.InvocationTargetException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Explicitly selected by the disposable MySQL runner; never consumes application DB settings. */
class BeautySchemaMigrationIT {
    MysqlDataSource source;

    @BeforeEach
    void isolatedDatabase() throws SQLException {
        String url = System.getProperty("beauty.test.admin-url");
        assertNotNull(url, "Run scripts/verify-beauty-persistence.mjs, which owns the disposable database");
        assertTrue(url.startsWith("jdbc:mysql://127.0.0.1:"));
        var admin = new MysqlDataSource();
        admin.setUrl(url);
        admin.setUser("root");
        String database = "beauty_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = admin.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_bin");
        }
        source = new MysqlDataSource();
        source.setUrl(url.replace("/mysql?", "/" + database + "?"));
        source.setUser("root");
    }

    void initialize(String mode) {
        Class<?> type = assertDoesNotThrow(() -> Class.forName("ai.neargo.shop.beauty.config.BeautySchemaValidator"),
                "Beauty must provide its own scoped Flyway migration and validation");
        try {
            type.getMethod("initialize", DataSource.class, String.class)
                    .invoke(type.getConstructor().newInstance(), source, mode);
        } catch (InvocationTargetException ex) {
            if (ex.getCause() instanceof RuntimeException cause) throw cause;
            throw new AssertionError(ex.getCause());
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }

    private void execute(String sql) throws SQLException {
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private int count(String sql) throws SQLException {
        try (var connection = source.getConnection(); var statement = connection.createStatement();
                var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }

    @Test
    void migratesValidatesAndDoesNotRepeat() throws SQLException {
        initialize("migrate");
        initialize("validate");
        initialize("migrate");
        assertEquals(19, count("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()"));
        assertEquals(1, count("SELECT COUNT(*) FROM bty_flyway_history WHERE version='1' AND success=1"));
        assertEquals(0, count("SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema=DATABASE() AND table_name='flyway_schema_history'"));
    }

    @Test
    void refusesUntrackedSchemaInsteadOfBaseliningIt() throws SQLException {
        execute("CREATE TABLE bty_work_order (id BIGINT)");
        assertThrows(IllegalStateException.class, () -> initialize("migrate"));
        assertEquals(1, count("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()"));
    }

    @Test
    void validateDoesNotInitializeAnEmptyDatabase() throws SQLException {
        assertThrows(IllegalStateException.class, () -> initialize("validate"));
        assertEquals(0, count("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()"));
    }

    @Test
    void rejectsChangedAppliedMigration() throws SQLException {
        initialize("migrate");
        execute("UPDATE bty_flyway_history SET checksum=123 WHERE version='1'");
        assertThrows(RuntimeException.class, () -> initialize("validate"));
    }

    @Test
    void detectsMissingColumnAndUnknownTables() throws SQLException {
        initialize("migrate");
        execute("ALTER TABLE bty_store_policy DROP COLUMN status");
        assertThrows(IllegalStateException.class, () -> initialize("validate"));
        execute("ALTER TABLE bty_store_policy ADD COLUMN status VARCHAR(24) NOT NULL");
        execute("CREATE TABLE bty_unknown (id BIGINT)");
        assertThrows(IllegalStateException.class, () -> initialize("validate"));
    }

    @Test
    void rejectsRemovedBusinessUniqueIndex() throws SQLException {
        initialize("migrate");
        execute("ALTER TABLE bty_source_allocation_guard DROP INDEX uk_bty_source_allocation_guard_2");
        assertThrows(IllegalStateException.class, () -> initialize("validate"));
    }
    @Test
    void rejectsChangedColumnTypeAndNullability() throws SQLException {
        initialize("migrate");
        execute("ALTER TABLE bty_store_policy MODIFY status VARCHAR(500) NULL");
        assertThrows(IllegalStateException.class, () -> initialize("validate"));
    }

    @Test
    void rejectsRemovedQuantityConstraint() throws SQLException {
        initialize("migrate");
        execute("ALTER TABLE bty_source_allocation_guard DROP CHECK ck_bty_source_allocation_guard_6");
        assertThrows(IllegalStateException.class, () -> initialize("validate"));
    }

    @Test
    void rejectsChangedBaseDefaults() throws SQLException {
        initialize("migrate");
        execute("ALTER TABLE bty_store_policy ALTER COLUMN deleted SET DEFAULT 1");
        assertThrows(IllegalStateException.class, () -> initialize("validate"));
    }

    @Test
    void rejectsRemovedPhysicalIdGeneration() throws SQLException {
        initialize("migrate");
        execute("ALTER TABLE bty_store_policy MODIFY id BIGINT NOT NULL");
        assertThrows(IllegalStateException.class, () -> initialize("validate"));
    }

    @Test
    void rejectsRemovedPrimaryKey() throws SQLException {
        initialize("migrate");
        execute("ALTER TABLE bty_store_policy ADD KEY physical_id (id)");
        execute("ALTER TABLE bty_store_policy DROP PRIMARY KEY");
        assertThrows(IllegalStateException.class, () -> initialize("validate"));
    }

    @Test
    void rejectsRemovedCalendarIndex() throws SQLException {
        initialize("migrate");
        execute("ALTER TABLE bty_appointment DROP INDEX idx_bty_appointment_1");
        assertThrows(IllegalStateException.class, () -> initialize("validate"));
    }

    @Test
    void rejectsChangedConstraintOperatorGrouping() throws SQLException {
        initialize("migrate");
        // A legal Boolean CHECK with identical tokens and a different operator tree.
        execute("ALTER TABLE bty_service_participation DROP CHECK ck_bty_service_participation_3");
        execute("ALTER TABLE bty_service_participation ADD CONSTRAINT ck_bty_service_participation_3 "
                + "CHECK ((ended_at IS NULL OR ended_at) >= started_at)");
        assertThrows(IllegalStateException.class, () -> initialize("validate"));
    }

    @Test
    void rejectsPrefixIndexReplacingFullStoreIdentity() throws SQLException {
        initialize("migrate");
        execute("ALTER TABLE bty_store_policy DROP INDEX uk_bty_store_policy_2, "
                + "ADD UNIQUE KEY uk_bty_store_policy_2 (tenant_no,entity_no,store_no(1))");
        assertThrows(IllegalStateException.class, () -> initialize("validate"));
    }

}

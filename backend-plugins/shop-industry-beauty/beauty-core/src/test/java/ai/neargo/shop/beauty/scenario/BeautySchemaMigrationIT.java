package ai.neargo.shop.beauty.scenario;

import com.mysql.cj.jdbc.MysqlDataSource;
import org.flywaydb.core.Flyway;
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
        assertEquals(28, count("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()"));
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

    @Test void v5ReceiptScopeAndChecksAreStrictlyValidated() throws SQLException {
        initialize("migrate");
        assertEquals(1, count("SELECT COUNT(*) FROM bty_flyway_history WHERE version='5' AND success=1"));
        execute("ALTER TABLE bty_guest_claim_receipt DROP INDEX uk_bty_guest_claim_receipt_1");
        assertThrows(IllegalStateException.class, () -> initialize("validate"));
    }
    @Test void v5ChallengeConstraintAndColumnsCannotDrift() throws SQLException {
        initialize("migrate");
        execute("ALTER TABLE bty_arrival_challenge DROP CHECK ck_bty_arrival_challenge_4");
        assertThrows(IllegalStateException.class, () -> initialize("validate"));
    }
    @Test void jdbcEnumRepresentationAndLiteralCaseDriftRemainStrict() throws SQLException {
        initialize("migrate");
        try (var connection = source.getConnection(); var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT check_clause FROM information_schema.check_constraints "
                        + "WHERE constraint_schema=DATABASE() AND constraint_name='ck_bty_reception_operation_3'")) {
            assertTrue(rows.next());
            String clause = rows.getString(1);
            // Integer codepoints distinguish JDBC raw characters from CLI/JSON escaping.
            System.out.println("V5_JDBC_CHECK_CODEPOINTS=" + clause.codePoints().boxed().toList());
            assertTrue(clause.contains("RECEIVED"));
        }
        execute("ALTER TABLE bty_reception_operation DROP CHECK ck_bty_reception_operation_3");
        execute("ALTER TABLE bty_reception_operation ADD CONSTRAINT ck_bty_reception_operation_3 "
                + "CHECK (phase IN ('received','RESOURCE_PENDING','ARRIVAL_PENDING',"
                + "'OPEN_PENDING','SUCCEEDED','REJECTED'))");
        assertThrows(IllegalStateException.class, () -> initialize("validate"));
    }

    @Test void identityPairRejectsHalfNullAndInvalidReferences() throws SQLException {
        initialize("migrate");
        assertDoesNotThrow(() -> insertIdentity(null, null));
        assertDoesNotThrow(() -> insertIdentity("USER", "U1"));
        assertDoesNotThrow(() -> insertIdentity("GUEST", "G1"));
        assertThrows(SQLException.class, () -> insertIdentity("USER", null));
        assertThrows(SQLException.class, () -> insertIdentity(null, "U1"));
        assertThrows(SQLException.class, () -> insertIdentity("OTHER", "U1"));
        assertThrows(SQLException.class, () -> insertIdentity("USER", ""));
    }
    private void insertIdentity(String kind, String ref) throws SQLException {
        try (var connection = source.getConnection(); var statement = connection.prepareStatement(
                "INSERT INTO bty_reception_operation (entity_no,store_no,operation_no,created_at,updated_at,"
                        + "actor_no,actor_realm,actor_kind,endpoint,action,key_hash,request_hash,phase,frozen_payload,"
                        + "identity_kind,identity_ref) VALUES ('E','S',?,NOW(),NOW(),'A','MERCHANT','MCH','BAPI-032',"
                        + "'CLAIM_GUEST',?,?,'RECEIVED','{}',?,?)")) {
            String id = UUID.randomUUID().toString().replace("-", "");
            statement.setString(1, id); statement.setString(2, id.repeat(2)); statement.setString(3, id.repeat(2));
            statement.setString(4, kind); statement.setString(5, ref); statement.executeUpdate();
        }
    }
    @Test void rejectsBacktickInsideCheckLiteral() throws SQLException {
        initialize("migrate");
        execute("ALTER TABLE bty_reception_operation DROP CHECK ck_bty_reception_operation_3");
        execute("ALTER TABLE bty_reception_operation ADD CONSTRAINT ck_bty_reception_operation_3 "
                + "CHECK (phase IN ('RECE`IVED','RESOURCE_PENDING','ARRIVAL_PENDING',"
                + "'OPEN_PENDING','SUCCEEDED','REJECTED'))");
        assertThrows(IllegalStateException.class, () -> initialize("validate"));
    }

    private void migrateThroughV5() {
        Flyway.configure().dataSource(source).locations("classpath:db/industry/beauty")
                .table("bty_flyway_history").target("5").baselineVersion("0").baselineOnMigrate(true)
                .createSchemas(false).placeholderReplacement(false).load().migrate();
    }
    @Test void validV5IdentityHistoryUpgradesWithoutRewrite() throws SQLException {
        migrateThroughV5(); insertIdentity(null, null); insertIdentity("USER", "U1"); insertIdentity("GUEST", "G1");
        int checksum = count("SELECT checksum FROM bty_flyway_history WHERE version='5'");
        assertThrows(RuntimeException.class, () -> initialize("validate"));
        initialize("migrate"); initialize("validate");
        assertEquals(3, count("SELECT COUNT(*) FROM bty_reception_operation"));
        assertEquals(checksum, count("SELECT checksum FROM bty_flyway_history WHERE version='5'"));
        assertEquals(1, count("SELECT COUNT(*) FROM bty_flyway_history WHERE version='6' AND success=1"));
        assertEquals(1, count("SELECT COUNT(*) FROM bty_reception_operation WHERE identity_kind IS NULL "
                + "AND identity_ref IS NULL"));
        assertEquals(1, count("SELECT COUNT(*) FROM bty_reception_operation WHERE identity_kind='USER' "
                + "AND identity_ref='U1'"));
    }
    @Test void invalidV5IdentityBlocksUpgradeWithoutRepairOrBaseline() throws SQLException {
        migrateThroughV5(); insertIdentity(null, "U1");
        assertThrows(RuntimeException.class, () -> initialize("migrate"));
        assertEquals(1, count("SELECT COUNT(*) FROM bty_reception_operation WHERE identity_kind IS NULL "
                + "AND identity_ref='U1'"));
        assertEquals(1, count("SELECT COUNT(*) FROM bty_flyway_history WHERE version='5' AND success=1"));
        assertEquals(0, count("SELECT COUNT(*) FROM bty_flyway_history WHERE version='6' AND success=1"));
        assertThrows(RuntimeException.class, () -> initialize("migrate"));
        assertThrows(RuntimeException.class, () -> initialize("validate"));
    }
    @Test void droppingV6GuardCannotPassValidation() throws SQLException {
        initialize("migrate");
        execute("ALTER TABLE bty_reception_operation DROP CHECK ck_bty_reception_operation_5");
        assertThrows(IllegalStateException.class, () -> initialize("validate"));
    }

}

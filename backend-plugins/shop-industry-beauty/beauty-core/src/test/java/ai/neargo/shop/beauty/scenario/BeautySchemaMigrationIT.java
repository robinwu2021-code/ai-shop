package ai.neargo.shop.beauty.scenario;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mysql.cj.jdbc.MysqlDataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.InvocationTargetException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.sql.DataSource;

/** Explicitly selected by the disposable MySQL runner; never consumes application DB settings. */
class BeautySchemaMigrationIT {
    MysqlDataSource source;

    @BeforeEach
    void isolatedDatabase() throws SQLException {
        String url = System.getProperty("beauty.test.admin-url");
        assertNotNull(
                url,
                "Run scripts/verify-beauty-persistence.mjs, which owns the disposable database");
        assertTrue(url.startsWith("jdbc:mysql://127.0.0.1:"));
        var admin = new MysqlDataSource();
        admin.setUrl(url);
        admin.setUser("root");
        admin.setPassword(System.getenv().getOrDefault("BEAUTY_NATIVE_MYSQL_PASSWORD", ""));
        String database = "beauty_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = admin.getConnection(); var statement = connection.createStatement()) {
            statement.execute(
                    "CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_bin");
        }
        source = new MysqlDataSource();
        source.setUrl(url.replace("/mysql?", "/" + database + "?"));
        source.setUser("root");
        source.setPassword(System.getenv().getOrDefault("BEAUTY_NATIVE_MYSQL_PASSWORD", ""));
    }

    void initialize(String mode) {
        Class<?> type =
                assertDoesNotThrow(
                        () -> Class.forName("ai.neargo.shop.beauty.config.BeautySchemaValidator"),
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
        assertEquals(
                29,
                count(
                        "SELECT COUNT(*) FROM information_schema.tables WHERE"
                            + " table_schema=DATABASE()"));
        assertEquals(
                1,
                count("SELECT COUNT(*) FROM bty_flyway_history WHERE version='1' AND success=1"));
        assertEquals(0, count("SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema=DATABASE() AND table_name='flyway_schema_history'"));
    }

    @Test
    void refusesUntrackedSchemaInsteadOfBaseliningIt() throws SQLException {
        execute("CREATE TABLE bty_work_order (id BIGINT)");
        assertThrows(IllegalStateException.class, () -> initialize("migrate"));
        assertEquals(
                1,
                count(
                        "SELECT COUNT(*) FROM information_schema.tables WHERE"
                            + " table_schema=DATABASE()"));
    }

    @Test
    void validateDoesNotInitializeAnEmptyDatabase() throws SQLException {
        assertThrows(IllegalStateException.class, () -> initialize("validate"));
        assertEquals(
                0,
                count(
                        "SELECT COUNT(*) FROM information_schema.tables WHERE"
                            + " table_schema=DATABASE()"));
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
        execute(
                "ALTER TABLE bty_source_allocation_guard DROP INDEX"
                    + " uk_bty_source_allocation_guard_2");
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
        execute(
                "ALTER TABLE bty_source_allocation_guard DROP CHECK"
                    + " ck_bty_source_allocation_guard_6");
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
        execute(
                "ALTER TABLE bty_service_participation ADD CONSTRAINT"
                    + " ck_bty_service_participation_3 CHECK ((ended_at IS NULL OR ended_at) >="
                    + " started_at)");
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
        assertEquals(
                1,
                count("SELECT COUNT(*) FROM bty_flyway_history WHERE version='5' AND success=1"));
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
        try (var connection = source.getConnection();
                var statement = connection.createStatement();
                var rows =
                        statement.executeQuery(
                                "SELECT check_clause FROM information_schema.check_constraints"
                                    + " WHERE constraint_schema=DATABASE() AND"
                                    + " constraint_name='ck_bty_reception_operation_3'")) {
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
        try (var connection = source.getConnection();
                var statement =
                        connection.prepareStatement(
                                "INSERT INTO bty_reception_operation"
                                    + " (entity_no,store_no,operation_no,created_at,updated_at,"
                                    + "actor_no,actor_realm,actor_kind,endpoint,action,key_hash,request_hash,"
                                    + "phase,frozen_payload,identity_kind,identity_ref) VALUES"
                                    + " ('E','S',?,NOW(),NOW(),'A','MERCHANT','MCH','BAPI-032',"
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

    @Test void v6AssignmentUnitsUpgradeWithoutRewrite() throws SQLException {
        Flyway.configure()
                .dataSource(source)
                .locations("classpath:db/industry/beauty")
                .table("bty_flyway_history")
                .target("6")
                .baselineVersion("0")
                .baselineOnMigrate(true)
                .createSchemas(false)
                .placeholderReplacement(false)
                .load()
                .migrate();
        insertAssignmentUnit("UNIT1");
        var jdbc = new JdbcTemplate(source);
        var before =
                jdbc.queryForMap(
                        "SELECT * FROM bty_appointment_assignment WHERE"
                            + " planned_assignment_no='UNIT1'");
        var history =
                jdbc.queryForList(
                        "SELECT version,checksum FROM bty_flyway_history ORDER BY installed_rank");
        assertThrows(SQLException.class, () -> insertAssignmentUnit("UNIT2"));
        assertThrows(RuntimeException.class, () -> initialize("validate"));
        initialize("migrate");
        initialize("validate");
        initialize("migrate");
        assertEquals(before, jdbc.queryForMap(
                "SELECT * FROM bty_appointment_assignment WHERE planned_assignment_no='UNIT1'"));
        assertEquals(
                history,
                jdbc.queryForList(
                        "SELECT version,checksum FROM bty_flyway_history WHERE version NOT IN"
                            + " ('7','8') ORDER BY installed_rank"));
        assertEquals(
                1,
                count("SELECT COUNT(*) FROM bty_flyway_history WHERE version='7' AND success=1"));
        insertAssignmentUnit("UNIT2");
        assertThrows(SQLException.class, () -> insertAssignmentUnit("UNIT1"));
        assertEquals(2, count("SELECT COUNT(*) FROM bty_appointment_assignment"));
        assertEquals(
                2,
                count(
                        "SELECT COUNT(DISTINCT planned_assignment_no) FROM"
                            + " bty_appointment_assignment"));
        assertEquals(
                6,
                count(
                        "SELECT COUNT(*) FROM information_schema.statistics WHERE"
                            + " table_schema=DATABASE() AND table_name='bty_appointment_assignment'"
                            + " AND index_name='idx_bty_appointment_assignment_1' AND"
                            + " non_unique=1"));
        initialize("validate");
    }

    private void insertAssignmentUnit(String number) throws SQLException {
        try (var connection = source.getConnection();
                var statement =
                        connection.prepareStatement(
                                "INSERT INTO"
                                    + " bty_appointment_assignment(entity_no,created_at,updated_at,"
                                    + "planned_assignment_no,"
                                    + "appointment_item_no,appointment_revision,requirement_no,resource_no,"
                                    + "hold_no,start_at,end_at,skill_snapshot) VALUES"
                                    + " ('E','2026-10-20 10:00:00','2026-10-20"
                                    + " 10:00:00',?,'ITEM',1,'REQ','ROOM1','HOLD','2026-10-20"
                                    + " 10:00:00','2026-10-20 11:00:00','{}')")) {
            statement.setString(1, number);
            statement.executeUpdate();
        }
    }

    @Test void v7AssignmentIndexDriftCannotPassValidation() throws SQLException {
        initialize("migrate");
        int checksum = count("SELECT checksum FROM bty_flyway_history WHERE version='7'");
        String table = "ALTER TABLE bty_appointment_assignment ";
        String drop = "DROP INDEX idx_bty_appointment_assignment_1";
        String columns =
                "(tenant_no,entity_no,appointment_item_no,appointment_revision,requirement_no,resource_no)";
        String add = "ADD INDEX idx_bty_appointment_assignment_1 " + columns;
        for (String drift :
                new String[] {"MISSING", "UNIQUE", "COLUMN_ORDER", "BUSINESS_UNIQUE", "CHECKSUM"}) {
            switch (drift) {
                case "MISSING" -> execute(table + drop);
                case "UNIQUE" -> execute(table + drop
                        + ", ADD UNIQUE INDEX idx_bty_appointment_assignment_1 " + columns);
                case "COLUMN_ORDER" ->
                        execute(
                                table
                                        + drop
                                        + ", ADD INDEX idx_bty_appointment_assignment_1 "
                                        + "(entity_no,tenant_no,appointment_item_no,appointment_revision,"
                                        + "requirement_no,resource_no)");
                case "BUSINESS_UNIQUE" ->
                        execute(table + "DROP INDEX uk_bty_appointment_assignment_1");
                case "CHECKSUM" ->
                        execute("UPDATE bty_flyway_history SET checksum=123 WHERE version='7'");
                default -> throw new AssertionError(drift);
            }
            assertThrows(RuntimeException.class, () -> initialize("validate"), drift);
            switch (drift) {
                case "MISSING" -> execute(table + add);
                case "UNIQUE", "COLUMN_ORDER" -> execute(table + drop + ", " + add);
                case "BUSINESS_UNIQUE" ->
                        execute(
                                table
                                        + "ADD UNIQUE INDEX uk_bty_appointment_assignment_1 "
                                        + "(tenant_no,entity_no,planned_assignment_no)");
                case "CHECKSUM" ->
                        execute(
                                "UPDATE bty_flyway_history SET checksum="
                                        + checksum
                                        + " WHERE version='7'");
                default -> throw new AssertionError(drift);
            }
            initialize("validate");
        }
    }

    private void migrateThroughV5() {
        Flyway.configure()
                .dataSource(source)
                .locations("classpath:db/industry/beauty")
                .table("bty_flyway_history")
                .target("5")
                .baselineVersion("0")
                .baselineOnMigrate(true)
                .createSchemas(false)
                .placeholderReplacement(false)
                .load()
                .migrate();
    }
    @Test void validV5IdentityHistoryUpgradesWithoutRewrite() throws SQLException {
        migrateThroughV5(); insertIdentity(null, null);
        insertIdentity("USER", "U1");
        insertIdentity("GUEST", "G1");
        int checksum = count("SELECT checksum FROM bty_flyway_history WHERE version='5'");
        assertThrows(RuntimeException.class, () -> initialize("validate"));
        initialize("migrate"); initialize("validate");
        assertEquals(3, count("SELECT COUNT(*) FROM bty_reception_operation"));
        assertEquals(checksum, count("SELECT checksum FROM bty_flyway_history WHERE version='5'"));
        assertEquals(
                1,
                count("SELECT COUNT(*) FROM bty_flyway_history WHERE version='6' AND success=1"));
        assertEquals(
                1,
                count(
                        "SELECT COUNT(*) FROM bty_reception_operation WHERE identity_kind IS NULL "
                                + "AND identity_ref IS NULL"));
        assertEquals(
                1,
                count(
                        "SELECT COUNT(*) FROM bty_reception_operation WHERE identity_kind='USER' "
                                + "AND identity_ref='U1'"));
    }
    @Test void invalidV5IdentityBlocksUpgradeWithoutRepairOrBaseline() throws SQLException {
        migrateThroughV5(); insertIdentity(null, "U1");
        assertThrows(RuntimeException.class, () -> initialize("migrate"));
        assertEquals(
                1,
                count(
                        "SELECT COUNT(*) FROM bty_reception_operation WHERE identity_kind IS NULL "
                                + "AND identity_ref='U1'"));
        assertEquals(
                1,
                count("SELECT COUNT(*) FROM bty_flyway_history WHERE version='5' AND success=1"));
        assertEquals(
                0,
                count("SELECT COUNT(*) FROM bty_flyway_history WHERE version='6' AND success=1"));
        assertThrows(RuntimeException.class, () -> initialize("migrate"));
        assertThrows(RuntimeException.class, () -> initialize("validate"));
    }
    @Test void droppingV6GuardCannotPassValidation() throws SQLException {
        initialize("migrate");
        execute("ALTER TABLE bty_reception_operation DROP CHECK ck_bty_reception_operation_5");
        assertThrows(IllegalStateException.class, () -> initialize("validate"));
    }

    @Test
    void v7HistoryUpgradesToV9WithoutRewritingIntentsOrHeldQuantity() throws SQLException {
        Flyway.configure()
                .dataSource(source)
                .locations("classpath:db/industry/beauty")
                .table("bty_flyway_history")
                .target("7")
                .baselineVersion("0")
                .baselineOnMigrate(true)
                .createSchemas(false)
                .placeholderReplacement(false)
                .load()
                .migrate();
        for (String state : new String[] {"RECEIVED", "BOUND"}) {
            execute(
                    "INSERT INTO"
                        + " bty_service_submission(entity_no,store_no,submission_no,action,request_key_hash,"
                        + "payload_hash,status,frozen_command,created_at,updated_at) VALUES"
                        + " ('E','S','"
                            + state
                            + "','OPEN',REPEAT('"
                            + (state.equals("BOUND") ? "b" : "a")
                            + "',64),REPEAT('c',64),'"
                            + state
                            + "','{}',NOW(),NOW())");
        }
        execute(
                "INSERT INTO"
                    + " bty_source_allocation_guard(entity_no,guard_no,source_kind,source_no,source_line_ref,"
                    + "source_revision,source_qty,allocated_qty,uom,source_hash,created_at,updated_at)"
                    + " VALUES"
                    + " ('E','G','STORE_ORDER','SUB','LINE',1,2,1,'COUNT',REPEAT('a',64),NOW(),NOW())");
        var jdbc = new JdbcTemplate(source);
        var intents = jdbc.queryForList("SELECT * FROM bty_service_submission ORDER BY id");
        var held = jdbc.queryForList("SELECT * FROM bty_source_allocation_guard");
        var history =
                jdbc.queryForList(
                        "SELECT version,checksum FROM bty_flyway_history ORDER BY installed_rank");
        assertThrows(RuntimeException.class, () -> initialize("validate"));
        initialize("migrate");
        initialize("validate");
        initialize("migrate");
        assertEquals(
                intents, jdbc.queryForList("SELECT * FROM bty_service_submission ORDER BY id"));
        String oldGuardColumns =
                held.getFirst().keySet().stream()
                        .map(name -> "`" + name + "`")
                        .collect(Collectors.joining(","));
        assertEquals(
                held,
                jdbc.queryForList(
                        "SELECT " + oldGuardColumns + " FROM bty_source_allocation_guard"));
        assertEquals(
                "LEGACY_FUNDED",
                jdbc.queryForObject(
                        "SELECT admission_kind FROM bty_source_allocation_guard", String.class));
        assertEquals(
                0,
                count(
                        "SELECT COUNT(*) FROM bty_source_allocation_guard WHERE"
                            + " manual_source_revision IS NOT NULL OR manual_source_hash IS NOT"
                            + " NULL"));
        assertEquals(
                history,
                jdbc.queryForList(
                        "SELECT version,checksum FROM bty_flyway_history "
                                + "WHERE CAST(version AS UNSIGNED)<=7 ORDER BY installed_rank"));
        assertEquals(0, count("SELECT COUNT(*) FROM bty_source_observation"));
        assertEquals(
                1,
                count("SELECT COUNT(*) FROM bty_flyway_history WHERE version='8' AND success=1"));
        assertEquals(
                1,
                count("SELECT COUNT(*) FROM bty_flyway_history WHERE version='9' AND success=1"));
    }

    @Test
    void v8RejectsHalfNullWatermarksAndConflictingCanonicalDomains() throws SQLException {
        initialize("migrate");
        String insert =
                "INSERT INTO"
                    + " bty_source_observation(entity_no,store_no,source_kind,source_no,checkout_caller,"
                    + "checkout_operation_no,order_no,buyer_no,state,failure_code,created_at,updated_at)"
                    + " VALUES"
                    + " ('E','S','STORE_ORDER','SUB','BEAUTY','OP','ORD','USER','UNKNOWN','READING',NOW(),NOW())";
        execute(insert);
        for (String mutation :
                new String[] {
                    "source_revision=1",
                    "source_hash=REPEAT('a',64)",
                    "verified_revision=1",
                    "verified_hash=REPEAT('a',64)",
                    "verified_lines='[]'",
                    "verified_at=NOW()",
                    "state='READY'",
                    "failure_code=NULL",
                    "deleted=1"
                }) {
            assertThrows(
                    SQLException.class,
                    () -> execute("UPDATE bty_source_observation SET " + mutation),
                    mutation);
        }
        assertThrows(SQLException.class, () -> execute(insert.replace("'OP'", "'OTHER'")));
        assertThrows(SQLException.class, () -> execute(insert.replace("'SUB'", "'OTHER'")));
        execute("UPDATE bty_source_observation SET source_revision=2,source_hash=REPEAT('b',64)");
        assertEquals(1, count("SELECT COUNT(*) FROM bty_source_observation WHERE state='UNKNOWN'"));
        initialize("validate");
    }

    @Test
    void v8JsonFunctionAndThresholdTamperingFailStructuralValidation() throws SQLException {
        initialize("migrate");
        var jdbc = new JdbcTemplate(source);
        String original =
                jdbc.queryForObject(
                                "SELECT CHECK_CLAUSE FROM information_schema.check_constraints"
                                    + " WHERE constraint_schema=DATABASE() AND"
                                    + " constraint_name='ck_bty_source_observation_6'",
                                String.class)
                        .replace("\\'", "'");
        for (String altered :
                new String[] {
                    original.replace("json_type", "json_unquote"),
                    original.replace("<= 20", "<= 21")
                }) {
            assertTrue(!original.equals(altered), original);
            execute("ALTER TABLE bty_source_observation DROP CHECK ck_bty_source_observation_6");
            execute(
                    "ALTER TABLE bty_source_observation ADD CONSTRAINT ck_bty_source_observation_6"
                        + " CHECK ("
                            + altered
                            + ")");
            assertThrows(IllegalStateException.class, () -> initialize("validate"));
            execute("ALTER TABLE bty_source_observation DROP CHECK ck_bty_source_observation_6");
            execute(
                    "ALTER TABLE bty_source_observation ADD CONSTRAINT ck_bty_source_observation_6"
                        + " CHECK ("
                            + original
                            + ")");
            initialize("validate");
        }
    }
}

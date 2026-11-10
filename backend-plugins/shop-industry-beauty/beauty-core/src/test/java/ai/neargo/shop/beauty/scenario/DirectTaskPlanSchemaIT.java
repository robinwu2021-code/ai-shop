package ai.neargo.shop.beauty.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.neargo.shop.beauty.policy.DirectTaskPlanSchemaVerifier;
import ai.neargo.shop.common.BizException;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;

/**
 * SOURCE only: absent V12 and partial physical installation must never become an unassigned
 * fallback.
 */
class DirectTaskPlanSchemaIT extends BeautyExecutionFixture {
    @Test
    void installedDirectPlanAuthorityRequiresActualForwardMigrationAndPhysicalObjects()
            throws Exception {
        initialize("BtyServiceTask");
        assertEquals(
                DirectTaskPlanSchemaVerifier.Installation.INSTALLED,
                new DirectTaskPlanSchemaVerifier(database.source).installation());
        sql.execute("DROP TRIGGER trg_bty_direct_plan_no_delete");
        var verifier = new DirectTaskPlanSchemaVerifier(database.source);
        assertEquals(DirectTaskPlanSchemaVerifier.Installation.CORRUPT, verifier.installation());
        assertThrows(BizException.class, verifier::requireAvailable);
    }

    @Test
    void priorRealV11InstallationIsAbsentRatherThanAClaimOfUnassignedReadiness() throws Exception {
        database = new BeautySchemaMigrationIT();
        database.isolatedDatabase();
        Flyway.configure()
                .dataSource(database.source)
                .locations("classpath:db/industry/beauty")
                .table("bty_flyway_history")
                .target("11")
                .load()
                .migrate();
        var verifier = new DirectTaskPlanSchemaVerifier(database.source);
        assertEquals(DirectTaskPlanSchemaVerifier.Installation.ABSENT, verifier.installation());
        assertThrows(BizException.class, verifier::requireAvailable);
    }

    @Test
    void checksumDamageAndUnknownHistoryCannotWeakenFrozenBaseValidation() throws Exception {
        initialize("BtyServiceTask");
        sql.update("UPDATE bty_flyway_history SET checksum=123 WHERE version='12'");
        assertEquals(
                DirectTaskPlanSchemaVerifier.Installation.CORRUPT,
                new DirectTaskPlanSchemaVerifier(database.source).installation());
        assertThrows(RuntimeException.class, () -> database.initialize("validate"));
    }

    @Test
    void unknownFutureHistoryIdentityIsRejected() throws Exception {
        initialize("BtyServiceTask");
        sql.update("UPDATE bty_flyway_history SET version='999' WHERE version='12'");
        assertThrows(RuntimeException.class, () -> database.initialize("validate"));
    }

    @Test
    void insertsIsolateEveryPartialPointerCombinationAndFullTripleValueChecks() throws Exception {
        initialize("BtyServiceTask");
        workOrder("WO", "S");
        insertPendingTask("ALL_NULL", null, null, null);
        var empty =
                sql.queryForMap(
                        "SELECT direct_plan_no,direct_plan_revision,direct_plan_hash"
                                + " FROM bty_service_task WHERE task_no='ALL_NULL'");
        for (String field : List.of("direct_plan_no", "direct_plan_revision", "direct_plan_hash")) {
            assertNull(empty.get(field), field);
        }
        insertPendingTask("FULL_VALID", "PLAN", 1L, "a".repeat(64));
        assertEquals(
                1L,
                sql.queryForObject(
                                "SELECT direct_plan_revision FROM bty_service_task"
                                        + " WHERE task_no='FULL_VALID'",
                                Long.class)
                        .longValue());
        var invalid =
                List.of(
                        new Pointer("PLAN", null, null),
                        new Pointer(null, 1L, null),
                        new Pointer(null, null, "a".repeat(64)),
                        new Pointer("PLAN", 1L, null),
                        new Pointer("PLAN", null, "a".repeat(64)),
                        new Pointer(null, 1L, "a".repeat(64)),
                        new Pointer("PLAN", 0L, "a".repeat(64)),
                        new Pointer("PLAN", -1L, "a".repeat(64)),
                        new Pointer("PLAN", 1L, "g".repeat(64)),
                        new Pointer("PLAN", 1L, "a".repeat(63)));
        for (int index = 0; index < invalid.size(); index++) {
            var pointer = invalid.get(index);
            String number = "INVALID_" + index;
            var failure =
                    assertThrows(
                            RuntimeException.class,
                            () ->
                                    insertPendingTask(
                                            number,
                                            pointer.number(),
                                            pointer.revision(),
                                            pointer.hash()));
            var cause = sqlFailure(failure);
            assertTrue(cause.getMessage().contains("ck_bty_task_direct_plan"), cause.getMessage());
            // MySQL CHECK=3819/HY000; MariaDB CHECK=4025/23000. Trigger SIGNAL is neither pair.
            assertTrue(
                    cause.getErrorCode() == 3819 && "HY000".equals(cause.getSQLState())
                            || cause.getErrorCode() == 4025 && "23000".equals(cause.getSQLState()),
                    cause.toString());
            assertEquals(
                    0,
                    sql.queryForObject(
                            "SELECT COUNT(*) FROM bty_service_task WHERE task_no=?",
                            Integer.class,
                            number));
        }
    }

    @Test
    void updatePublicationTriggerRejectsMissingOriginalOnOtherwiseValidPendingCountOneTask()
            throws Exception {
        initialize("BtyServiceTask");
        workOrder("WO", "S");
        insertPendingTask("PENDING", null, null, null);
        var failure =
                assertThrows(
                        RuntimeException.class,
                        () ->
                                sql.update(
                                        "UPDATE bty_service_task SET"
                                            + " direct_plan_no='MISSING',direct_plan_revision=1,direct_plan_hash=?,version=version+1"
                                            + " WHERE task_no='PENDING'",
                                        "a".repeat(64)));
        var cause = sqlFailure(failure);
        assertEquals("45000", cause.getSQLState());
        assertEquals(1644, cause.getErrorCode());
        assertTrue(
                cause.getMessage().contains("direct plan requires unfenced atomic publication"),
                cause.getMessage());
        var unchanged =
                sql.queryForMap(
                        "SELECT version,status,qty,started_at,direct_plan_no,direct_plan_revision,"
                                + "direct_plan_hash FROM bty_service_task WHERE task_no='PENDING'");
        assertEquals(0L, ((Number) unchanged.get("version")).longValue());
        assertEquals("PENDING", unchanged.get("status"));
        assertEquals(1, ((Number) unchanged.get("qty")).intValue());
        for (String field :
                List.of(
                        "started_at",
                        "direct_plan_no",
                        "direct_plan_revision",
                        "direct_plan_hash")) {
            assertNull(unchanged.get(field), field);
        }
    }

    /**
     * INSERT deliberately bypasses the UPDATE-only publication trigger so CHECK is the first
     * pointer guard.
     */
    private void insertPendingTask(String number, String planNo, Long revision, String hash) {
        seed(
                "bty_service_task",
                "task_no",
                number,
                "work_order_no",
                "WO",
                "scope_revision",
                1L,
                "sku_no",
                "SKU",
                "definition_version_no",
                "DV",
                "qty",
                1,
                "done_qty",
                0,
                "voided_qty",
                0,
                "uom",
                "COUNT",
                "status",
                "PENDING",
                "binding_revision",
                1L,
                "execution_snapshot",
                "{}",
                "direct_plan_no",
                planNo,
                "direct_plan_revision",
                revision,
                "direct_plan_hash",
                hash);
    }

    private SQLException sqlFailure(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SQLException sqlFailure) return sqlFailure;
        }
        throw new AssertionError("Expected a native SQLException", failure);
    }

    private record Pointer(String number, Long revision, String hash) {}
}

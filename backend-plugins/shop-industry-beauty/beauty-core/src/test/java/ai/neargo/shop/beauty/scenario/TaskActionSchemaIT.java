package ai.neargo.shop.beauty.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.neargo.shop.beauty.policy.TaskActionSchemaVerifier;
import ai.neargo.shop.common.BizException;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * SOURCE ONLY / NOT_RUN. Native CHECK tests INSERT; UPDATE/DELETE trigger tests are independent.
 */
class TaskActionSchemaIT extends BeautyExecutionFixture {
    @Test
    void historicalV12IsOptionalAbsentAndFullV13HasExactHistoryAndObjects() throws Exception {
        database = new BeautySchemaMigrationIT();
        database.isolatedDatabase();
        Flyway.configure()
                .dataSource(database.source)
                .locations("classpath:db/industry/beauty")
                .table("bty_flyway_history")
                .target("12")
                .load()
                .migrate();
        var verifier = new TaskActionSchemaVerifier(database.source);
        assertEquals(TaskActionSchemaVerifier.Installation.ABSENT, verifier.installation());
        assertThrows(BizException.class, verifier::requireAvailable);
        database.initialize("validate");
        database.initialize("migrate");
        assertEquals(TaskActionSchemaVerifier.Installation.INSTALLED, verifier.installation());
        database.initialize("validate");
    }

    @Test
    void partialGuardMarkerAndTamperedHistoryAreClosedNotAbsent() throws Exception {
        for (String damage :
                List.of(
                        "DROP TRIGGER trg_bty_task_action_no_delete",
                        "ALTER TABLE bty_service_submission DROP CHECK ck_bty_task_action",
                        "UPDATE bty_flyway_history SET checksum=123 WHERE version='13'",
                        "UPDATE bty_flyway_history SET success=0 WHERE version='13'")) {
            initialize("BtyServiceSubmission");
            sql.execute(damage);
            var verifier = new TaskActionSchemaVerifier(database.source);
            assertEquals(
                    TaskActionSchemaVerifier.Installation.CORRUPT, verifier.installation(), damage);
            assertThrows(BizException.class, verifier::requireAvailable);
            assertThrows(RuntimeException.class, () -> database.initialize("validate"));
        }
    }

    @Test
    void nativeCheckRejectsEachMalformedInsertByTheExactConstraint() throws Exception {
        initialize("BtyServiceSubmission");
        for (String mutation :
                List.of(
                        "schema",
                        "purpose",
                        "actionNo",
                        "context.scope.tenantNo",
                        "context.scope.entityNo",
                        "context.scope.storeNo",
                        "context.workOrderNo",
                        "intentHash",
                        "expected.scopeRevision",
                        "expected.taskVersion",
                        "slot.ordinal",
                        "status",
                        "retry",
                        "failure")) {
            var json = JsonMapper.builder().build();
            var root = json.readTree(valid("BAD"));
            String status = "TASK_INTENDED";
            Object retry = null;
            String failure = null;
            if (mutation.equals("status")) status = "RECEIVED";
            else if (mutation.equals("retry")) retry = T0;
            else if (mutation.equals("failure")) failure = "SOURCE_UNKNOWN";
            else {
                var parts = mutation.split("\\.");
                var parent = root;
                for (int i = 0; i < parts.length - 1; i++) parent = parent.get(parts[i]);
                ((tools.jackson.databind.node.ObjectNode) parent).putNull(parts[parts.length - 1]);
            }
            String frozen = json.writeValueAsString(root);
            String state = status;
            Object at = retry;
            String code = failure;
            var rejected =
                    assertThrows(
                            RuntimeException.class, () -> insert("BAD", frozen, state, at, code));
            var nativeFailure = sqlFailure(rejected);
            assertEquals(3819, nativeFailure.getErrorCode(), mutation);
            assertEquals("HY000", nativeFailure.getSQLState(), mutation);
            assertTrue(
                    nativeFailure.getMessage().contains("ck_bty_task_action"),
                    nativeFailure.getMessage());
        }
        insert("VALID", valid("VALID"), "TASK_INTENDED", null, null);
        assertEquals(
                1,
                sql.queryForObject(
                        "SELECT COUNT(*) FROM bty_service_submission WHERE action='TASK_ACTION'",
                        Integer.class));
    }

    @Test
    void immutableUpdateDeleteAndFamilyConversionHaveTheirOwnNativeGuards() throws Exception {
        initialize("BtyServiceSubmission");
        insert("ORIGINAL", valid("ORIGINAL"), "TASK_INTENDED", null, null);
        for (String change :
                List.of(
                        "status='RECOVERING'",
                        "version=version+1",
                        "deleted=1",
                        "action='START'",
                        "request_key_hash='other'",
                        "frozen_command='{}'")) {
            var rejected =
                    assertThrows(
                            RuntimeException.class,
                            () ->
                                    sql.update(
                                            "UPDATE bty_service_submission SET "
                                                    + change
                                                    + " WHERE submission_no='ORIGINAL'"));
            var nativeFailure = sqlFailure(rejected);
            assertEquals(1644, nativeFailure.getErrorCode());
            assertEquals("45000", nativeFailure.getSQLState());
            assertTrue(nativeFailure.getMessage().contains("task action original is immutable"));
        }
        var deleted =
                assertThrows(
                        RuntimeException.class,
                        () ->
                                sql.update(
                                        "DELETE FROM bty_service_submission WHERE"
                                            + " submission_no='ORIGINAL'"));
        assertTrue(sqlFailure(deleted).getMessage().contains("task action original is permanent"));
        seed(
                "bty_service_submission",
                "submission_no",
                "LEGACY",
                "store_no",
                "S",
                "work_order_no",
                "WO",
                "action",
                "ADD_SOURCE",
                "request_key_hash",
                "legacy",
                "payload_hash",
                "b".repeat(64),
                "status",
                "RECEIVED",
                "frozen_command",
                "{}");
        var conversion =
                assertThrows(
                        RuntimeException.class,
                        () ->
                                sql.update(
                                        "UPDATE bty_service_submission SET action='TASK_ACTION'"
                                            + " WHERE submission_no='LEGACY'"));
        assertTrue(
                sqlFailure(conversion).getMessage().contains("task action original is immutable"));
    }

    private void insert(String no, String frozen, String status, Object retry, String failure) {
        seed(
                "bty_service_submission",
                "submission_no",
                no,
                "store_no",
                "S",
                "work_order_no",
                "WO",
                "action",
                "TASK_ACTION",
                "request_key_hash",
                "key",
                "payload_hash",
                "a".repeat(64),
                "status",
                status,
                "frozen_command",
                frozen,
                "next_retry_at",
                retry,
                "failure_code",
                failure);
    }

    /**
     * This is only a structurally legal row for native CHECK isolation, not a claim of parent/SELF
     * validity.
     */
    private String valid(String no) {
        return JsonMapper.builder()
                .build()
                .writeValueAsString(
                        Map.of(
                                "schema",
                                "TASK_ACTION_V1",
                                "purpose",
                                "START_TASK",
                                "actionNo",
                                no,
                                "context",
                                Map.of(
                                        "scope",
                                        Map.of("tenantNo", "MAIN", "entityNo", "E", "storeNo", "S"),
                                        "workOrderNo",
                                        "WO",
                                        "taskNo",
                                        "TASK"),
                                "requestKeyHash",
                                "key",
                                "intentHash",
                                "a".repeat(64),
                                "namespaceHash",
                                "b".repeat(64),
                                "targetHash",
                                "c".repeat(64),
                                "expected",
                                Map.of(
                                        "scopeRevision",
                                        1,
                                        "taskVersion",
                                        0,
                                        "bindingRevision",
                                        1,
                                        "bindingVersion",
                                        0,
                                        "sourceFenceRevision",
                                        1),
                                "slot",
                                Map.of(
                                        "requirementNo",
                                        "R",
                                        "roleCode",
                                        "MAIN",
                                        "sourceLineRef",
                                        "TASK:R:0",
                                        "personnelNo",
                                        "P",
                                        "resourceNo",
                                        "RS",
                                        "ordinal",
                                        0)));
    }

    private SQLException sqlFailure(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlFailure) return sqlFailure;
        }
        throw new AssertionError("Expected actual MySQL SQLException", failure);
    }
}

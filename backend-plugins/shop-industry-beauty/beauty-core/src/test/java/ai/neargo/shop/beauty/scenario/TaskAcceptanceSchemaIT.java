package ai.neargo.shop.beauty.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.neargo.shop.beauty.policy.TaskAcceptanceSchemaVerifier;
import ai.neargo.shop.common.BizException;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * SOURCE ONLY / NOT_RUN. Native CHECK tests INSERT; UPDATE/DELETE trigger tests are independent.
 */
class TaskAcceptanceSchemaIT extends BeautyExecutionFixture {
    @Test
    void historicalV13IsOptionalAbsentAndFullV14HasExactHistoryAndObjects() throws Exception {
        database = new BeautySchemaMigrationIT();
        database.isolatedDatabase();
        Flyway.configure()
                .dataSource(database.source)
                .locations("classpath:db/industry/beauty")
                .table("bty_flyway_history")
                .target("13")
                .load()
                .migrate();
        var verifier = new TaskAcceptanceSchemaVerifier(database.source);
        assertEquals(TaskAcceptanceSchemaVerifier.Installation.ABSENT, verifier.installation());
        assertThrows(BizException.class, verifier::requireAvailable);
        database.initialize("validate");
        database.initialize("migrate");
        assertEquals(TaskAcceptanceSchemaVerifier.Installation.INSTALLED, verifier.installation());
        database.initialize("validate");
    }

    @Test
    void partialGuardMarkerAndTamperedHistoryAreClosedNotAbsent() throws Exception {
        for (String damage :
                List.of(
                        "DROP TRIGGER trg_bty_task_accept_no_delete",
                        "ALTER TABLE bty_service_submission DROP CHECK ck_bty_task_accept",
                        "UPDATE bty_flyway_history SET checksum=123 WHERE version='14'",
                        "UPDATE bty_flyway_history SET success=0 WHERE version='14'")) {
            initialize("BtyServiceSubmission");
            sql.execute(damage);
            var verifier = new TaskAcceptanceSchemaVerifier(database.source);
            assertEquals(
                    TaskAcceptanceSchemaVerifier.Installation.CORRUPT,
                    verifier.installation(),
                    damage);
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
                        "recordHash",
                        "requestVersions.scopeRevision",
                        "requestVersions.taskVersion",
                        "publishedTaskRevision",
                        "status",
                        "retry",
                        "failure")) {
            var json = JsonMapper.builder().build();
            var root = json.readTree(valid("BAD"));
            String status = "TASK_ACCEPTED";
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
                    nativeFailure.getMessage().contains("ck_bty_task_accept"),
                    nativeFailure.getMessage());
        }
        insert("VALID", valid("VALID"), "TASK_ACCEPTED", null, null);
        assertEquals(
                1,
                sql.queryForObject(
                        "SELECT COUNT(*) FROM bty_service_submission WHERE action='TASK_ACCEPT'",
                        Integer.class));
    }

    @Test
    void immutableUpdateDeleteAndFamilyConversionHaveTheirOwnNativeGuards() throws Exception {
        initialize("BtyServiceSubmission");
        insert("ORIGINAL", valid("ORIGINAL"), "TASK_ACCEPTED", null, null);
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
            assertTrue(
                    nativeFailure.getMessage().contains("task acceptance original is immutable"));
        }
        var deleted =
                assertThrows(
                        RuntimeException.class,
                        () ->
                                sql.update(
                                        "DELETE FROM bty_service_submission WHERE"
                                            + " submission_no='ORIGINAL'"));
        assertTrue(
                sqlFailure(deleted).getMessage().contains("task acceptance original is permanent"));
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
                                        "UPDATE bty_service_submission SET action='TASK_ACCEPT'"
                                            + " WHERE submission_no='LEGACY'"));
        assertTrue(
                sqlFailure(conversion)
                        .getMessage()
                        .contains("task acceptance original is immutable"));
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
                "TASK_ACCEPT",
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
        var root = JsonMapper.builder().build().createObjectNode();
        root.put("schema", "TASK_ACCEPT_V1");
        root.put("purpose", "ACCEPT_TASK");
        root.put("actionNo", no);
        root.put("requestKeyHash", "key");
        root.put("requestHash", "d".repeat(64));
        root.put("recordHash", "a".repeat(64));
        root.put("actorNamespace", "b".repeat(64));
        root.put("requiredRosterHash", "c".repeat(64));
        root.put("publishedTaskRevision", 1);
        var context = root.putObject("context");
        context.put("workOrderNo", "WO");
        context.put("taskNo", "TASK");
        var scope = context.putObject("scope");
        scope.put("tenantNo", "MAIN");
        scope.put("entityNo", "E");
        scope.put("storeNo", "S");
        var versions = root.putObject("requestVersions");
        versions.put("scopeRevision", 1);
        versions.put("taskVersion", 0);
        versions.put("bindingRevision", 1);
        versions.put("bindingVersion", 0);
        versions.put("sourceFenceRevision", 1);
        var selected = root.putArray("selected").addObject();
        selected.put("expectedAssignmentRevision", 0);
        var slot = selected.putObject("slot");
        slot.put("requirementNo", "REQ");
        slot.put("ordinal", 0);
        slot.put("roleCode", "MAIN");
        slot.put("sourceLineRef", "TASK:REQ:0");
        slot.put("personnelNo", "P");
        slot.put("resourceNo", "R");
        slot.put("startAt", "2026-11-01T10:00:00Z");
        slot.put("endAt", "2026-11-01T10:30:00Z");
        var accepted = root.putArray("result").addObject();
        accepted.put("assignmentNo", "AS");
        accepted.put("requirementNo", "REQ");
        accepted.put("ordinal", 0);
        accepted.put("roleCode", "MAIN");
        accepted.put("assignmentRevision", 1);
        accepted.put("validUntil", "2026-11-01T10:30:00Z");
        accepted.put("currentState", "CURRENT_OBSERVATION_REQUIRED");
        var qualification = root.putObject("qualification");
        qualification.put("schema", "RESOURCE_QUALIFICATION_OBSERVATION_V1");
        qualification.put("outcome", "PROVEN");
        qualification.putArray("groups").addObject();
        return JsonMapper.builder().build().writeValueAsString(root);
    }

    @Test
    void slotNotNullUniqueRevisionAndExpiryAreNativeConstraints() throws Exception {
        initialize("BtyTaskAssignmentSlot", "BtyServiceSubmission");
        insertSlot("SLOT1", "REQ", 0, 1, T0.plusMinutes(30));
        assertThrows(
                RuntimeException.class, () -> insertSlot("SLOT2", "REQ", 0, 1, T0.plusMinutes(30)));
        assertThrows(
                RuntimeException.class, () -> insertSlot("SLOT3", null, 1, 1, T0.plusMinutes(30)));
        var revision =
                assertThrows(
                        RuntimeException.class,
                        () -> insertSlot("SLOT4", "OTHER", 0, 0, T0.plusMinutes(30)));
        assertTrue(sqlFailure(revision).getMessage().contains("ck_bty_assignment_state"));
        var expiry =
                assertThrows(RuntimeException.class, () -> insertSlot("SLOT5", "OTHER", 0, 1, T0));
        assertTrue(sqlFailure(expiry).getMessage().contains("ck_bty_assignment_state"));
        assertEquals(
                1,
                sql.queryForObject("SELECT COUNT(*) FROM bty_task_assignment_slot", Integer.class));
    }

    private void insertSlot(
            String no, String requirement, int ordinal, long revision, LocalDateTime until) {
        seed(
                "bty_task_assignment_slot",
                "store_no",
                "S",
                "work_order_no",
                "WO",
                "task_no",
                "TASK",
                "assignment_no",
                no,
                "requirement_no",
                requirement,
                "role_ordinal",
                ordinal,
                "role_code",
                "MAIN",
                "personnel_no",
                "P",
                "resource_no",
                "R",
                "plan_kind",
                "DIRECT",
                "plan_no",
                "PLAN",
                "plan_revision",
                1,
                "plan_hash",
                "a".repeat(64),
                "reference_hash",
                "b".repeat(64),
                "accept_action_no",
                "ACCEPT",
                "accept_record_hash",
                "c".repeat(64),
                "qualification_hash",
                "d".repeat(64),
                "assignment_revision",
                revision,
                "accepted_at",
                T0,
                "valid_until",
                until);
    }

    private static SQLException sqlFailure(Throwable failure) {
        while (failure != null && !(failure instanceof SQLException)) {
            failure = failure.getCause();
        }
        return (SQLException) failure;
    }
}

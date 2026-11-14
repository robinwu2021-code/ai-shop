package ai.neargo.shop.beauty.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.neargo.shop.beauty.mapper.TaskWorkflowMapper;
import ai.neargo.shop.beauty.policy.TaskWorkflowSchemaVerifier;

import org.junit.jupiter.api.Test;

/**
 * Actual MySQL forward migration, immutable source journal and root CAS; no synthetic owner
 * success.
 */
class TaskWorkflowPersistenceIT extends BeautyExecutionFixture {
    @Test
    void actualForwardSchemaKeepsFrozenActionAndTerminalObservationImmutable() throws Exception {
        initialize("BtyServiceSubmission");
        assertTrue(TaskWorkflowSchemaVerifier.installed(database.source));
        workOrder("WO", "S");
        String command =
                """
{"schema":"BEAUTY_TASK_WORKFLOW_V1","operationNo":"FLOW","frozenHash":"%s","keyHash":"%s",
 "start":{"action":{"context":{"scope":{"tenantNo":"MAIN","entityNo":"E","storeNo":"S"},
 "workOrderNo":"WO","taskNo":"TASK"}}},"request":{"action":"PAUSE"}}
"""
                        .formatted("a".repeat(64), "b".repeat(64));
        seed(
                "bty_service_submission",
                "submission_no",
                "FLOW",
                "store_no",
                "S",
                "work_order_no",
                "WO",
                "action",
                "TASK_FLOW",
                "request_key_hash",
                "b".repeat(64),
                "payload_hash",
                "a".repeat(64),
                "status",
                "INTENDED",
                "frozen_command",
                command,
                "workflow_observation_json",
                "{\"resource\":null,\"humanCommitted\":false,\"cancellationRequested\":false,\"fulfilled\":false}");
        assertThrows(
                RuntimeException.class,
                () ->
                        sql.update(
                                "UPDATE bty_service_submission SET frozen_command='{}',version=1"
                                        + " WHERE submission_no='FLOW'"));
        sql.update(
                "UPDATE bty_service_submission SET"
                        + " workflow_observation_json=?,status='APPLIED',version=1 WHERE"
                        + " submission_no='FLOW'",
                "{\"resource\":{\"resultNo\":\"REAL\"},\"humanCommitted\":false,\"cancellationRequested\":false,\"fulfilled\":false}");
        assertThrows(
                RuntimeException.class,
                () ->
                        sql.update(
                                "UPDATE bty_service_submission SET"
                                        + " workflow_observation_json=?,version=2 WHERE"
                                        + " submission_no='FLOW'",
                                "{\"resource\":{\"resultNo\":\"OTHER\"},\"humanCommitted\":false,\"cancellationRequested\":false,\"fulfilled\":false}"));
        sql.update(
                "UPDATE bty_service_submission SET workflow_observation_json=?,version=2 WHERE"
                        + " submission_no='FLOW'",
                "{\"resource\":{\"resultNo\":\"REAL\"},\"humanCommitted\":false,\"cancellationRequested\":false,\"fulfilled\":true}");
        assertThrows(
                RuntimeException.class,
                () ->
                        sql.update(
                                "UPDATE bty_service_submission SET"
                                        + " workflow_observation_json=?,version=3 WHERE"
                                        + " submission_no='FLOW'",
                                "{\"resource\":{\"resultNo\":\"REAL\"},\"humanCommitted\":false,\"cancellationRequested\":false,\"fulfilled\":false}"));
    }

    @Test
    void actualRootTransitionsRejectStaleVersionsAndUnfinishedTasks() throws Exception {
        initialize("BtyServiceTask");
        workOrder("WO", "S");
        task("TASK", "WO");
        sql.update(
                "UPDATE bty_work_order SET status='OPEN',started_at=NULL WHERE work_order_no='WO'");
        runner().run(
                        c ->
                                tx(
                                        c,
                                        () -> {
                                            var mapper = c.getBean(TaskWorkflowMapper.class);
                                            assertEquals(
                                                    0,
                                                    mapper.startRoot(
                                                            "E", "OTHER", "WO", 0, 1, "ACTOR", T0));
                                            assertEquals(
                                                    0,
                                                    mapper.startRoot(
                                                            "E", "S", "WO", 1, 1, "ACTOR", T0));
                                            assertEquals(
                                                    1,
                                                    mapper.startRoot(
                                                            "E", "S", "WO", 0, 1, "ACTOR", T0));
                                            assertEquals(
                                                    0,
                                                    mapper.startRoot(
                                                            "E", "S", "WO", 0, 1, "ACTOR", T0));
                                            assertEquals(
                                                    0,
                                                    mapper.finishRoot(
                                                            "E",
                                                            "S",
                                                            "WO",
                                                            1,
                                                            1,
                                                            "ACTOR",
                                                            T0.plusHours(1)));
                                            return null;
                                        }));
        assertEquals(
                "IN_SERVICE",
                sql.queryForObject(
                        "SELECT status FROM bty_work_order WHERE work_order_no='WO'",
                        String.class));
    }
}

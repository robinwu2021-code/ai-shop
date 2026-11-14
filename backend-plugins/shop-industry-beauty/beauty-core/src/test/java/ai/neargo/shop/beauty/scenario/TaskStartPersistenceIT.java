package ai.neargo.shop.beauty.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.neargo.shop.beauty.entity.BtyServiceSubmission;
import ai.neargo.shop.beauty.mapper.BeautyMappers;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * SOURCE ONLY / NOT_RUN. Actual Beauty mapper/CAS; no synthetic Native/Core success is asserted.
 */
class TaskStartPersistenceIT extends BeautyExecutionFixture {
    @Test
    void originalExecutionHasOneGateAndOrdinaryAcceptanceSeesItsProtection() throws Exception {
        initialize("BtyServiceSubmission");
        workOrder("WO", "S");
        TaskStartSchemaRows.insert(this, "GATE", "EXEC");
        assertThrows(
                RuntimeException.class, () -> TaskStartSchemaRows.insert(this, "ANOTHER", "EXEC"));
        runner().run(
                        c -> {
                            var mapper = c.getBean(BeautyMappers.ServiceSubmissionMapper.class);
                            assertEquals(1, mapper.protectedTaskStarts("E", "S", "TASK"));
                            assertEquals(0, mapper.protectedTaskStarts("E", "OTHER", "TASK"));
                            assertEquals(
                                    "GATE",
                                    mapper.taskStartForExecution("E", "S", "EXEC")
                                            .getSubmissionNo());
                        });
    }

    @Test
    void mapperUsesTheExactPhaseAndVersionAndStaleWritesCannotWin() throws Exception {
        initialize("BtyServiceSubmission");
        workOrder("WO", "S");
        TaskStartSchemaRows.insert(this, "GATE", "EXEC");
        runner().run(
                        c ->
                                tx(
                                        c,
                                        () -> {
                                            var mapper =
                                                    c.getBean(
                                                            BeautyMappers.ServiceSubmissionMapper
                                                                    .class);
                                            BtyServiceSubmission row =
                                                    mapper.selectScopedForUpdate("E", "S", "GATE");
                                            var json = JsonMapper.builder().build();
                                            var o =
                                                    (ObjectNode)
                                                            json.readTree(
                                                                    row.getActionObservationJson());
                                            o.put("version", 1)
                                                    .put("phase", "DISPATCHING")
                                                    .put("pendingReason", "NATIVE_RESULT_PENDING");
                                            row.setActionObservationJson(
                                                    json.writeValueAsString(o));
                                            row.setStatus("DISPATCHING");
                                            row.setFailureCode("NATIVE_RESULT_PENDING");
                                            row.setNextRetryAt(T0.plusSeconds(30));
                                            row.setUpdatedAt(T0);
                                            row.setUpdatedBy("TEST");
                                            assertEquals(
                                                    1,
                                                    mapper.advanceTaskStart(
                                                            row, 0, "SOURCE_FROZEN"));
                                            assertEquals(
                                                    0,
                                                    mapper.advanceTaskStart(
                                                            row, 0, "SOURCE_FROZEN"));
                                            assertEquals(
                                                    0,
                                                    mapper.advanceTaskStart(
                                                            row, 1, "SOURCE_FROZEN"));
                                            return null;
                                        }));
        assertEquals(
                1L,
                sql.queryForObject(
                        "SELECT version FROM bty_service_submission WHERE submission_no='GATE'",
                        Long.class));
    }
}

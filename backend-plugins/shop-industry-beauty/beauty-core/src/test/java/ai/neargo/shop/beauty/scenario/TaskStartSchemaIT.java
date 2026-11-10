package ai.neargo.shop.beauty.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.neargo.shop.beauty.policy.TaskAcceptanceSchemaVerifier;
import ai.neargo.shop.beauty.policy.TaskActionSchemaVerifier;
import ai.neargo.shop.beauty.policy.TaskStartSchemaVerifier;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

/**
 * SOURCE ONLY / NOT_RUN. Only the disposable Beauty schema is used; rows below are CHECK-isolation
 * data.
 */
class TaskStartSchemaIT extends BeautyExecutionFixture {
    @Test
    void historicalV14StaysUsableAndTheExactV15SuccessorInstalls() throws Exception {
        database = new BeautySchemaMigrationIT();
        database.isolatedDatabase();
        Flyway.configure()
                .dataSource(database.source)
                .locations("classpath:db/industry/beauty")
                .table("bty_flyway_history")
                .target("14")
                .load()
                .migrate();
        assertEquals(
                TaskStartSchemaVerifier.Installation.ABSENT,
                new TaskStartSchemaVerifier(database.source).installation());
        assertEquals(
                TaskActionSchemaVerifier.Installation.INSTALLED,
                new TaskActionSchemaVerifier(database.source).installation());
        assertEquals(
                TaskAcceptanceSchemaVerifier.Installation.INSTALLED,
                new TaskAcceptanceSchemaVerifier(database.source).installation());
        database.initialize("validate");
        database.initialize("migrate");
        database.initialize("validate");
        assertEquals(
                TaskStartSchemaVerifier.Installation.INSTALLED,
                new TaskStartSchemaVerifier(database.source).installation());
        assertEquals(
                TaskActionSchemaVerifier.Installation.INSTALLED,
                new TaskActionSchemaVerifier(database.source).installation());
        assertEquals(
                TaskAcceptanceSchemaVerifier.Installation.INSTALLED,
                new TaskAcceptanceSchemaVerifier(database.source).installation());
    }

    @Test
    void checkRejectsMissingExecutionAndDoesNotRelyOnUpdateTrigger() throws Exception {
        initialize("BtyServiceSubmission");
        assertThrows(RuntimeException.class, () -> insert("BAD", null));
        insert("GOOD", "EXEC");
        assertEquals(
                1,
                sql.queryForObject(
                        "SELECT COUNT(*) FROM bty_service_submission WHERE action='TASK_START'",
                        Integer.class));
    }

    @Test
    void originalUpdateAndDeleteCannotEraseTheGate() throws Exception {
        initialize("BtyServiceSubmission");
        insert("GATE", "EXEC");
        assertThrows(
                RuntimeException.class,
                () -> sql.update("DELETE FROM bty_service_submission WHERE submission_no='GATE'"));
        assertThrows(
                RuntimeException.class,
                () ->
                        sql.update(
                                "UPDATE bty_service_submission SET"
                                        + " execution_no='OTHER',version=version+1 WHERE"
                                        + " submission_no='GATE'"));
        assertThrows(
                RuntimeException.class,
                () ->
                        sql.update(
                                "UPDATE bty_service_submission SET status='ACKED',version=version+1"
                                        + " WHERE submission_no='GATE'"));
        assertThrows(
                RuntimeException.class,
                () ->
                        sql.update(
                                "UPDATE bty_service_submission SET version=version+2 WHERE"
                                        + " submission_no='GATE'"));
    }

    @Test
    void missingGuardOrChangedHistoryCannotMasqueradeAsLegacyAbsence() throws Exception {
        initialize("BtyServiceSubmission");
        sql.execute("DROP TRIGGER trg_bty_task_start_no_delete");
        assertEquals(
                TaskStartSchemaVerifier.Installation.CORRUPT,
                new TaskStartSchemaVerifier(database.source).installation());
        assertThrows(RuntimeException.class, () -> database.initialize("validate"));
    }

    void insert(String no, String execution) {
        TaskStartSchemaRows.insert(this, no, execution);
    }
}

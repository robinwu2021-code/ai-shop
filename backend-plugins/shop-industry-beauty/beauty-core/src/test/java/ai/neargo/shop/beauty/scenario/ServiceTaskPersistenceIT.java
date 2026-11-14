package ai.neargo.shop.beauty.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.BeanWrapperImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

class ServiceTaskPersistenceIT extends BeautyExecutionFixture {
    @BeforeEach
    void database() throws Exception {
        initialize("BtyWorkOrder", "BtyServiceTask", "BtyServiceStep", "BtyServiceParticipation");
        workOrder("W", "S");
        task("T", "W");
        binding(1);
    }
    private void binding(long revision) {
        seed("bty_trade_binding", "binding_no", "B" + revision, "submission_no", "SUB", "task_no", "T",
                "binding_revision", revision, "source_kind", "STORE_ORDER", "source_no", "ORDER",
                "source_line_ref", "LINE", "source_revision", 1L, "qty", 2, "uom", "COUNT",
                "source_snapshot_hash", "H".repeat(64), "guard_no", "GUARD");
    }
    private Object completion(ApplicationContext context, long version, long binding, String quantity) {
        return call(context, "ServiceTaskMapper", "recordCompletion", "E", "S", "T", version, binding,
                new BigDecimal(quantity), T0.plusHours(1), "ACTOR", T0.plusHours(1));
    }
    private void step(String number, int sequence, String status) {
        seed("bty_service_step", "step_no", number, "task_no", "T", "step_code", number,
                "sequence_no", sequence, "status", status, "started_at", T0,
                "finished_at", status.equals("DONE") ? T0.plusMinutes(10) : null);
    }
    private void participation() {
        seed("bty_service_participation", "participation_no", "P", "task_no", "T", "personnel_no", "TECH",
                "assignment_no", "EMPLOYMENT", "role_code", "TECH", "started_at", T0,
                "qualification_snapshot", "{}");
    }
    @Test
    void rejectsOverCompletionAndStaleBindingAndKeepsPartialWorkOpen() {
        step("SERVICE", 0, "DONE");
        runner().run(context -> {
            assertThat(tx(context, () -> completion(context, 0, 2, "1"))).isEqualTo(0);
            assertThat(tx(context, () -> completion(context, 0, 1, "-1"))).isEqualTo(0);
            assertThat(tx(context, () -> completion(context, 0, 1, "3"))).isEqualTo(0);
            assertThat(tx(context, () -> completion(context, 0, 1, "1"))).isEqualTo(1);
            assertThat(sql.queryForObject("SELECT status FROM bty_service_task", String.class)).isEqualTo("IN_SERVICE");
            assertThat(tx(context, () -> completion(context, 0, 1, "1"))).isEqualTo(0);
            assertThat(tx(context, () -> completion(context, 1, 1, "1"))).isEqualTo(1);
            assertThat(sql.queryForObject("SELECT status FROM bty_service_task", String.class)).isEqualTo("DONE");
            assertThat(sql.queryForObject("SELECT updated_by FROM bty_service_task", String.class)).isEqualTo("ACTOR");
        });
    }
    @Test
    void freezeBlocksTaskMutationAndScopeAdvance() {
        runner().run(context -> {
            assertThat(tx(context, () -> call(context, "WorkOrderMapper", "freezeScope", "E", "S", "W", 0L, 1L,
                    "ACTOR", T0))).isEqualTo(1);
            assertThat(tx(context, () -> call(context, "WorkOrderMapper", "advanceScope", "E", "S", "W", 1L, 1L,
                    "ACTOR", T0))).isEqualTo(0);
            assertThat(tx(context, () -> completion(context, 0, 1, "1"))).isEqualTo(0);
        });
    }
    @Test
    void requiresCompletedEarlierStepsBeforeCompletingTask() {
        step("FIRST", 0, "IN_PROGRESS");
        step("SECOND", 1, "IN_PROGRESS");
        runner().run(context -> {
            assertThat(tx(context, () -> completion(context, 0, 1, "2"))).isEqualTo(0);
            assertThat(tx(context, () -> call(context, "ServiceStepMapper", "completeStep", "E", "S", "SECOND", "T",
                    0L, T0.plusMinutes(5), "{}", "ACTOR", T0))).isEqualTo(0);
            assertThat(tx(context, () -> call(context, "ServiceStepMapper", "completeStep", "E", "S", "FIRST", "T",
                    0L, T0.plusMinutes(5), "{}", "ACTOR", T0))).isEqualTo(1);
            assertThat(tx(context, () -> call(context, "ServiceStepMapper", "completeStep", "E", "S", "SECOND", "T",
                    0L, T0.plusMinutes(6), "{}", "ACTOR", T0))).isEqualTo(1);
            assertThat(tx(context, () -> completion(context, 0, 1, "2"))).isEqualTo(1);
        });
    }
    @Test
    void closesOnlyCurrentParticipationAndNeverOverwritesPersonnel() {
        participation();
        runner().run(context -> {
            assertThat(tx(context, () -> call(context, "ServiceParticipationMapper", "endParticipation", "E", "S", "P",
                    0L, T0.minusMinutes(1), "handover", "ACTOR", T0))).isEqualTo(0);
            assertThat(tx(context, () -> call(context, "ServiceParticipationMapper", "endParticipation", "E",
                    "OTHER", "P", 0L, T0.plusMinutes(1), "handover", "ACTOR", T0))).isEqualTo(0);
            assertThat(tx(context, () -> call(context, "ServiceParticipationMapper", "endParticipation", "E", "S", "P",
                    0L, T0.plusMinutes(1), "handover", "ACTOR", T0))).isEqualTo(1);
            assertThat(tx(context, () -> call(context, "ServiceParticipationMapper", "endParticipation", "E", "S", "P",
                    1L, T0.plusMinutes(2), "again", "ACTOR", T0))).isEqualTo(0);
            assertThat(sql.queryForObject("SELECT personnel_no FROM bty_service_participation", String.class))
                    .isEqualTo("TECH");
        });
    }
    @Test
    void advancesBindingsOnlyToExistingCompleteAllocation() {
        runner().run(context -> {
            assertThat(tx(context, () -> call(context, "ServiceTaskMapper", "switchBindingRevision", "E", "S", "T",
                    0L, 1L, 2L, "ACTOR", T0))).isEqualTo(0);
            binding(2);
            assertThat(tx(context, () -> call(context, "ServiceTaskMapper", "switchBindingRevision", "E", "S", "T",
                    0L, 1L, 2L, "ACTOR", T0))).isEqualTo(1);
            assertThat(tx(context, () -> call(context, "ServiceTaskMapper", "switchBindingRevision", "E", "S", "T",
                    1L, 2L, 1L, "ACTOR", T0))).isEqualTo(0);
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM bty_trade_binding", Integer.class)).isEqualTo(2);
        });
    }
    @Test
    void closeRechecksRealChildrenAndCurrentBindingInsteadOfOnlyParentCounters() {
        participation();
        step("STEP", 0, "DONE");
        sql.execute("UPDATE bty_work_order SET status='SERVICE_FINISHED',"
                + "finished_at=DATE_ADD(started_at,INTERVAL 1 HOUR),scope_frozen=1");
        runner().run(context -> {
            assertThat(tx(context, () -> close(context))).isEqualTo(0);
            sql.execute("UPDATE bty_service_task SET status='DONE',done_qty=qty,"
                    + "finished_at=DATE_ADD(started_at,INTERVAL 1 HOUR)");
            assertThat(tx(context, () -> close(context))).isEqualTo(0);
            sql.execute("UPDATE bty_service_participation SET ended_at=started_at");
            sql.execute("UPDATE bty_service_task SET binding_revision=2");
            assertThat(tx(context, () -> close(context))).isEqualTo(0);
            sql.execute("UPDATE bty_service_task SET binding_revision=1");
            assertThat(tx(context, () -> close(context))).isEqualTo(1);
            assertThat(sql.queryForObject("SELECT status FROM bty_work_order", String.class)).isEqualTo("CLOSED");
        });
    }
    private Object close(ApplicationContext context) {
        return call(context, "WorkOrderMapper", "closeFrozenScope", "E", "S", "W", 0L, 1L,
                T0.plusHours(2), "ACTOR", T0.plusHours(2));
    }
    @Test
    void disallowsCompletionStatusShortcutAndWrongEntityParent() {
        runner().run(context -> {
            assertThat(tx(context, () -> call(context, "ServiceTaskMapper", "changeStatus", "E", "S", "T", 0L,
                    1L, "IN_SERVICE", "DONE", T0.plusMinutes(1), "ACTOR", T0))).isEqualTo(0);
            sql.execute("UPDATE bty_work_order SET entity_no='FOREIGN'");
            assertThat(tx(context, () -> completion(context, 0, 1, "1"))).isEqualTo(0);
            assertThat(tx(context, () -> call(context, "ServiceTaskMapper", "selectScopedForUpdate", "E", "S", "T")))
                    .isNull();
        });
    }
    @Test
    void transactionRollbackRestoresCompletionFacts() {
        step("STEP", 0, "DONE");
        runner().run(context -> {
            assertThatThrownBy(() -> tx(context, () -> {
                assertThat(completion(context, 0, 1, "1")).isEqualTo(1);
                throw new IllegalStateException("rollback");
            })).hasMessage("rollback");
            assertThat(sql.queryForObject("SELECT done_qty FROM bty_service_task", BigDecimal.class))
                    .isEqualByComparingTo("0");
            assertThat(sql.queryForObject("SELECT version FROM bty_service_task", Long.class)).isZero();
        });
    }
    @Test
    void rejectsUnrepresentableQuantityInsteadOfReportingZeroWorkAsSuccess() {
        step("STEP", 0, "DONE");
        runner().run(context -> assertThat(tx(context, () -> completion(context, 0, 1, "0.0000001"))).isEqualTo(0));
    }
    @Test
    void cannotFinishWithoutTheRequiredStepRows() {
        runner().run(context -> assertThat(tx(context, () -> completion(context, 0, 1, "2"))).isEqualTo(0));
    }
    @Test
    void closeCannotPredateActualChildCompletion() {
        step("STEP", 0, "DONE");
        sql.execute("UPDATE bty_work_order SET status='SERVICE_FINISHED',scope_frozen=1,finished_at=started_at");
        sql.execute("UPDATE bty_service_task SET status='DONE',done_qty=qty,"
                + "finished_at=DATE_ADD(started_at,INTERVAL 3 HOUR)");
        runner().run(context -> assertThat(tx(context, () -> close(context))).isEqualTo(0));
    }

    @Test
    void scopeAdvanceUsesIndependentRootAndScopeRevisions() {
        runner().run(context -> {
            assertThat(tx(context, () -> call(context, "WorkOrderMapper", "advanceScope", "E", "S", "W", 0L, 1L,
                    "ACTOR", T0))).isEqualTo(1);
            assertThat(tx(context, () -> call(context, "WorkOrderMapper", "advanceScope", "E", "S", "W", 1L, 1L,
                    "ACTOR", T0))).isEqualTo(0);
            Object root = tx(context, () -> call(context, "WorkOrderMapper", "selectScopedForUpdate", "E", "S", "W"));
            var properties = new BeanWrapperImpl(root);
            assertThat(properties.getPropertyValue("scopeFrozen")).isEqualTo(false);
            assertThat(properties.getPropertyValue("scopeRevision")).isEqualTo(2L);
            assertThat(properties.getPropertyValue("version")).isEqualTo(1L);
            // A task created in the previous scope remains executable until the root is frozen.
            assertThat(tx(context, () -> completion(context, 0, 1, "1"))).isEqualTo(1);
        });
    }

    @Test
    void taskStartPauseResumePreservesOriginalStartAndRejectsStaleVersion() {
        sql.execute("UPDATE bty_service_task SET status='PENDING',started_at=NULL");
        runner().run(context -> {
            assertThat(tx(context, () -> status(context, 0, "PENDING", "IN_SERVICE"))).isEqualTo(1);
            assertThat(tx(context, () -> status(context, 0, "IN_SERVICE", "PAUSED"))).isEqualTo(0);
            assertThat(tx(context, () -> status(context, 1, "IN_SERVICE", "PAUSED"))).isEqualTo(1);
            assertThat(tx(context, () -> status(context, 2, "PAUSED", "IN_SERVICE"))).isEqualTo(1);
            Object task = tx(context, () -> call(context, "ServiceTaskMapper", "selectScopedForUpdate", "E", "S", "T"));
            assertThat(new BeanWrapperImpl(task).getPropertyValue("startedAt")).isEqualTo(T0.plusMinutes(1));
        });
    }

    @Test
    void voidRequiresTaskThatNeverStarted() {
        sql.execute("UPDATE bty_service_task SET status='PENDING',voided_qty=qty");
        runner().run(context -> {
            assertThat(tx(context, () -> status(context, 0, "PENDING", "VOIDED"))).isEqualTo(0);
            sql.execute("UPDATE bty_service_task SET started_at=NULL");
            assertThat(tx(context, () -> status(context, 0, "PENDING", "VOIDED"))).isEqualTo(1);
        });
    }

    private Object status(ApplicationContext context, long version, String before, String after) {
        return call(context, "ServiceTaskMapper", "changeStatus", "E", "S", "T", version,
                1L, before, after, T0.plusMinutes(1), "ACTOR", T0.plusMinutes(1));
    }

    @Test
    void rootLockSerializesFreezeBeforeTaskCompletion() {
        runner().run(context -> {
            var rootLocked = new CountDownLatch(1);
            var completionAttempted = new CountDownLatch(1);
            try (var workers = Executors.newFixedThreadPool(2)) {
                var freeze = workers.submit(() -> tx(context, () -> {
                    assertThat(call(context, "WorkOrderMapper", "selectScopedForUpdate", "E", "S", "W")).isNotNull();
                    rootLocked.countDown();
                    await(completionAttempted);
                    return call(context, "WorkOrderMapper", "freezeScope", "E", "S", "W", 0L, 1L, "ACTOR", T0);
                }));
                var complete = workers.submit(() -> {
                    await(rootLocked);
                    completionAttempted.countDown();
                    return tx(context, () -> {
                        call(context, "WorkOrderMapper", "selectScopedForUpdate", "E", "S", "W");
                        call(context, "ServiceTaskMapper", "selectScopedForUpdate", "E", "S", "T");
                        return completion(context, 0, 1, "1");
                    });
                });
                assertThat(freeze.get(15, TimeUnit.SECONDS)).isEqualTo(1);
                assertThat(complete.get(15, TimeUnit.SECONDS)).isEqualTo(0);
                assertThat(sql.queryForObject("SELECT done_qty FROM bty_service_task", BigDecimal.class))
                        .isEqualByComparingTo("0");
            }
        });
    }

    private void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(ex);
        }
    }

    @Test
    void cannotStartFullyVoidedTaskWithEmptyBinding() {
        fullyVoidedPendingTask();
        runner().run(context -> {
            assertThat(tx(context, () -> status(context, 0, "PENDING", "IN_SERVICE"))).isEqualTo(0);
            assertThat(sql.queryForObject("SELECT status FROM bty_service_task", String.class)).isEqualTo("PENDING");
            assertThat(sql.queryForObject("SELECT version FROM bty_service_task", Long.class)).isZero();
            assertThat(tx(context, () -> status(context, 0, "PENDING", "VOIDED"))).isEqualTo(1);
        });
    }

    @Test
    void cannotSwitchFullyVoidedTaskToAnEmptyBindingRevision() {
        fullyVoidedPendingTask();
        runner().run(context -> {
            assertThat(tx(context, () -> call(context, "ServiceTaskMapper", "switchBindingRevision", "E", "S", "T",
                    0L, 1L, 2L, "ACTOR", T0))).isEqualTo(0);
            assertThat(sql.queryForObject("SELECT binding_revision FROM bty_service_task", Long.class)).isEqualTo(1L);
            assertThat(sql.queryForObject("SELECT version FROM bty_service_task", Long.class)).isZero();
        });
    }

    private void fullyVoidedPendingTask() {
        sql.execute("UPDATE bty_service_task SET status='PENDING',voided_qty=qty,started_at=NULL");
        sql.execute("UPDATE bty_trade_binding SET deleted=1");
    }

}

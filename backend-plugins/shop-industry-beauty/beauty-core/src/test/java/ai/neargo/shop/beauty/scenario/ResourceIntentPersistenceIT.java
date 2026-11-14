package ai.neargo.shop.beauty.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.spi.resource.ResourcePlanContract;
import ai.neargo.shop.spi.resource.ResourceSourceAuthorityPort;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.context.ApplicationContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Real persistence primitives only; seeded plans and terminal rows do not constitute source authorization. */
class ResourceIntentPersistenceIT extends BeautyExecutionFixture {
    private String plan;
    @BeforeEach
    void database() throws Exception {
        initialize("BtyAppointmentResourceIntent", "BtyAppointmentResourceFence");
        appointment("A", "S");
        var interval = new ResourcePlanContract.Interval(T0.toInstant(ZoneOffset.UTC),
                T0.plusHours(1).toInstant(ZoneOffset.UTC));
        plan = " \n" + ResourcePlanContract.encode(List.of(new ResourcePlanContract.Demand("R", "I", 1,
                List.of(interval))), List.of(new ResourcePlanContract.Qualification("R", "I", null, null, false)))
                + "\n ";
        intent("I1", "CMD1", plan);
        seed("bty_appointment_resource_fence", "fence_no", "F", "appointment_no", "A", "store_no", "S");
    }
    private void intent(String no, String command, String payload) {
        seed("bty_appointment_resource_intent", "intent_no", no, "appointment_no", "A", "store_no", "S",
                "command_no", command, "source_revision", 0, "action", "REPLACE", "command_hash", "a".repeat(64),
                "plan_hash", ResourcePlanContract.hash(plan == null ? payload : plan), "frozen_plan", payload,
                "phase_code", 0, "initiated_by", "ACTOR");
    }
    private int acquire(ApplicationContext ctx, String store, String no, long version) {
        call(ctx, "AppointmentMapper", "selectScopedForUpdate", "E", store, "A");
        return (Integer) call(ctx, "AppointmentResourceFenceMapper", "acquire", "E", store, "A", no, version,
                "ACTOR", T0);
    }
    @Test
    void preservesExactPlanBytesAndRejectsDuplicateCommandOrInvalidPayload() {
        assertThat(sql.queryForObject("SELECT frozen_plan FROM bty_appointment_resource_intent", String.class))
                .isEqualTo(plan);
        assertThatThrownBy(() -> intent("I2", "CMD1", plan)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> intent("I3", "CMD3", "{")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> intent("I4", "CMD4", "\"" + "中".repeat(22000) + "\""))
                .isInstanceOf(RuntimeException.class);
    }
    @Test
    void serializesOneActiveFenceAndKeepsScopeAndRevision() {
        intent("I2", "CMD2", plan);
        runner().run(ctx -> {
            assertThat(tx(ctx, () -> acquire(ctx, "OTHER", "I1", 0))).isZero();
            assertThat(tx(ctx, () -> acquire(ctx, "S", "I1", 0))).isOne();
            assertThat(tx(ctx, () -> acquire(ctx, "S", "I2", 1))).isZero();
            assertThat(tx(ctx, () -> acquire(ctx, "S", "I1", 0))).isZero();
            assertThat(sql.queryForObject("SELECT active_intent_no FROM bty_appointment_resource_fence",
                    String.class)).isEqualTo("I1");
            assertThat(sql.queryForObject("SELECT version FROM bty_appointment_resource_fence", Long.class)).isOne();
        });
    }
    @Test
    void concurrentCommandsCannotAcquireTheSameAppointment() {
        intent("I2", "CMD2", plan);
        runner().run(ctx -> {
            var barrier = new CyclicBarrier(2);
            try (var threads = Executors.newFixedThreadPool(2)) {
                var first = threads.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return tx(ctx, () -> acquire(ctx, "S", "I1", 0));
                });
                var second = threads.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return tx(ctx, () -> acquire(ctx, "S", "I2", 0));
                });
                int one = first.get(15, TimeUnit.SECONDS);
                int two = second.get(15, TimeUnit.SECONDS);
                assertThat(one + two).isOne();
                assertThat(sql.queryForObject("SELECT active_intent_no FROM bty_appointment_resource_fence",
                        String.class)).isEqualTo(one == 1 ? "I1" : "I2");
            }
        });
    }

    @Test
    void rejectsInvalidPhaseAndTerminalShapes() {
        assertThatThrownBy(() -> sql.update("UPDATE bty_appointment_resource_intent SET phase_code=1"))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> sql.update("UPDATE bty_appointment_resource_intent SET terminal_code=1"))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> sql.update("UPDATE bty_appointment_resource_intent SET source_revision=-1"))
                .isInstanceOf(RuntimeException.class);
        runner().run(ctx -> {
            assertThat(tx(ctx, () -> acquire(ctx, "S", "I1", 0))).isOne();
            assertThat(tx(ctx, () -> call(ctx, "AppointmentResourceIntentMapper", "authorize", "E", "OTHER", "I1",
                    0L, "D", T0, T0.plusSeconds(1), "ACTOR", T0))).isEqualTo(0);
            assertThat(tx(ctx, () -> call(ctx, "AppointmentResourceIntentMapper", "authorize", "E", "S", "I1", 0L,
                    "D", T0, T0, "ACTOR", T0))).isEqualTo(0);
            assertThat(tx(ctx, () -> call(ctx, "AppointmentResourceIntentMapper", "reconcile", "E", "S", "I1", 0L,
                    1, "b".repeat(64), "ACTOR", T0))).isEqualTo(0);
        });
    }

    @Test
    void blocksScheduleCancelConfirmAndArrivalEvenWhenPermissionHasExpired() {
        runner().run(ctx -> {
            assertThat(tx(ctx, () -> acquire(ctx, "S", "I1", 0))).isOne();
            assertThat(tx(ctx, () -> call(ctx, "AppointmentResourceIntentMapper", "authorize", "E", "S", "I1", 0L,
                    "D", T0, T0.plusSeconds(1), "ACTOR", T0))).isEqualTo(1);
            assertThat(tx(ctx, () -> call(ctx, "AppointmentMapper", "changeSchedule", "E", "S", "A", 0L,
                    "CONFIRMED", T0.plusHours(2), T0.plusHours(3), "NEXT", "ACTOR", T0))).isEqualTo(0);
            assertThat(tx(ctx, () -> call(ctx, "AppointmentMapper", "changeStatus", "E", "S", "A", 0L,
                    "CONFIRMED", "CANCELLED", "ACTOR", T0))).isEqualTo(0);
            assertThat(tx(ctx, () -> call(ctx, "AppointmentMapper", "markArrived", "E", "S", "A", 0L,
                    "CONFIRMED", T0, "ACTOR", T0))).isEqualTo(0);
            sql.update("UPDATE bty_appointment SET status='HELD'");
            assertThat(tx(ctx, () -> call(ctx, "AppointmentMapper", "changeStatus", "E", "S", "A", 0L,
                    "HELD", "CONFIRMED", "ACTOR", T0))).isEqualTo(0);
            sql.update("UPDATE bty_appointment_resource_fence SET deleted=1");
            assertThat(tx(ctx, () -> call(ctx, "AppointmentMapper", "changeStatus", "E", "S", "A", 0L,
                    "HELD", "CANCELLED", "ACTOR", T0))).isEqualTo(0);
        });
    }
    @Test
    void requiresReconciledTerminalEvidenceBeforeFenceRelease() {
        runner().run(ctx -> {
            assertThat(tx(ctx, () -> acquire(ctx, "S", "I1", 0))).isOne();
            assertThat(tx(ctx, () -> call(ctx, "AppointmentResourceFenceMapper", "releaseReconciled", "E", "S",
                    "A", "I1", 1L, "ACTOR", T0))).isEqualTo(0);
            assertThat(tx(ctx, () -> call(ctx, "AppointmentResourceIntentMapper", "authorize", "E", "S", "I1", 0L,
                    "D", T0, T0.plusSeconds(1), "ACTOR", T0))).isEqualTo(1);
            assertThat(tx(ctx, () -> call(ctx, "AppointmentResourceFenceMapper", "releaseReconciled", "E", "S",
                    "A", "I1", 1L, "ACTOR", T0.plusDays(1)))).isEqualTo(0);
            assertThat(tx(ctx, () -> call(ctx, "AppointmentResourceIntentMapper", "reconcile", "E", "S", "I1", 0L,
                    1, "b".repeat(64), "ACTOR", T0))).isEqualTo(0);
            assertThat(tx(ctx, () -> call(ctx, "AppointmentResourceIntentMapper", "reconcile", "E", "S", "I1", 1L,
                    1, "b".repeat(64), "ACTOR", T0))).isEqualTo(1);
            assertThat(tx(ctx, () -> call(ctx, "AppointmentResourceFenceMapper", "releaseReconciled", "E", "S",
                    "A", "I1", 0L, "ACTOR", T0))).isEqualTo(0);
            assertThat(tx(ctx, () -> call(ctx, "AppointmentResourceFenceMapper", "releaseReconciled", "E", "S",
                    "A", "I1", 1L, "ACTOR", T0))).isEqualTo(1);
            assertThat(sql.queryForObject("SELECT active_intent_no FROM bty_appointment_resource_fence",
                    String.class)).isNull();
            assertThat(tx(ctx, () -> call(ctx, "AppointmentMapper", "changeStatus", "E", "S", "A", 0L,
                    "CONFIRMED", "CANCELLED", "ACTOR", T0))).isEqualTo(1);
        });
    }
    @Test
    void persistsDeniedContractWithoutInventingPermissionAndReconcilesOnlyRejection() {
        var binding = new ResourceSourceAuthorityPort.Binding("MAIN", "E", "S", "CMD1", "BEAUTY_APPOINTMENT",
                "A", 0, "REPLACE", "a".repeat(64), ResourcePlanContract.hash(plan));
        var denied = new ResourceSourceAuthorityPort.Decision(binding, ResourceSourceAuthorityPort.State.DENIED,
                "D", null, null, null, null, "SOURCE_DENIED");
        runner().run(ctx -> {
            assertThat(tx(ctx, () -> acquire(ctx, "S", "I1", 0))).isOne();
            // Seed only the storage shape of the real common contract; this is not a source decision service.
            sql.update("UPDATE bty_appointment_resource_intent SET phase_code=2, decision_no=?, reason_code=?, "
                    + "version=1", denied.decisionNo(), denied.reasonCode());
            assertThat(sql.queryForObject("SELECT issued_at FROM bty_appointment_resource_intent", String.class))
                    .isNull();
            assertThatThrownBy(() -> sql.update("UPDATE bty_appointment_resource_intent "
                    + "SET issued_at=?, valid_until=?", T0, T0.plusSeconds(1))).isInstanceOf(RuntimeException.class);
            assertThat(tx(ctx, () -> call(ctx, "AppointmentResourceIntentMapper", "reconcile", "E", "S", "I1", 1L,
                    1, "b".repeat(64), "ACTOR", T0))).isEqualTo(0);
            assertThat(tx(ctx, () -> call(ctx, "AppointmentResourceIntentMapper", "reconcile", "E", "S", "I1", 1L,
                    2, "b".repeat(64), "ACTOR", T0))).isEqualTo(1);
            assertThat(sql.queryForObject("SELECT reason_code FROM bty_appointment_resource_intent", String.class))
                    .isEqualTo("SOURCE_DENIED");
            assertThat(tx(ctx, () -> call(ctx, "AppointmentResourceFenceMapper", "releaseReconciled", "E", "S",
                    "A", "I1", 1L, "ACTOR", T0))).isEqualTo(1);
        });
    }

    @Test
    void rollbackLeavesNoFenceAndSourceRevisionMismatchCannotAcquire() {
        runner().run(ctx -> {
            assertThatThrownBy(() -> tx(ctx, () -> {
                assertThat(acquire(ctx, "S", "I1", 0)).isOne();
                throw new IllegalStateException("rollback");
            })).hasMessage("rollback");
            assertThat(sql.queryForObject("SELECT active_intent_no FROM bty_appointment_resource_fence",
                    String.class)).isNull();
            sql.update("UPDATE bty_appointment SET version=1");
            assertThat(tx(ctx, () -> acquire(ctx, "S", "I1", 0))).isZero();
        });
    }
}

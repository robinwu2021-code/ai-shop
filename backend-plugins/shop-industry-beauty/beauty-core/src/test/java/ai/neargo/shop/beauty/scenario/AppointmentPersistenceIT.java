package ai.neargo.shop.beauty.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.common.data.scope.DataScopeSpec;
import ai.neargo.shop.auth.ScopeDim;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

class AppointmentPersistenceIT extends BeautyExecutionFixture {
    @BeforeEach
    void database() throws Exception {
        initialize("BtyAppointment", "BtyAppointmentItem");
        appointment("A", "S");
        seed("ord_appointment_item", "appointment_item_no", "I", "appointment_no", "A", "sku_no", "SKU",
                "definition_version_no", "DV", "qty", 1, "uom", "COUNT", "sequence_no", 0,
                "requested_personnel_no", "REQUESTED", "execution_snapshot", "{}");
    }
    private int change(ApplicationContext context, String store, long version, String expected) {
        return (Integer) call(context, "AppointmentMapper", "changeSchedule", "E", store, "A", version, expected,
                T0.plusHours(2), T0.plusHours(3), "NEW-HOLD", "ACTOR", T0.plusMinutes(1));
    }
    /** 改约后项目行上「顾客指定的人员」不变（人员安排在修订快照里，TDD §2.6 第 7 项）。 */
    @Test
    void scheduleChangeKeepsRequestedPerson() {
        runner().run(context -> {
            assertThat(tx(context, () -> change(context, "S", 0, "CONFIRMED"))).isEqualTo(1);
            assertThat(sql.queryForObject("SELECT requested_personnel_no FROM ord_appointment_item", String.class))
                    .isEqualTo("REQUESTED");
        });
    }
    @Test
    void rejectsStaleScheduleAndWrongStoreOrTerminalState() {
        runner().run(context -> {
            assertThat(tx(context, () -> change(context, "OTHER", 0, "CONFIRMED"))).isEqualTo(0);
            assertThat(tx(context, () -> change(context, "S", 7, "CONFIRMED"))).isEqualTo(0);
            sql.execute("UPDATE ord_appointment SET status='CHECKED_IN'");
            assertThat(tx(context, () -> change(context, "S", 0, "CHECKED_IN"))).isEqualTo(0);
        });
    }
    @Test
    void marksArrivedOnceAndRejectsArbitraryStatusJumps() {
        runner().run(context -> {
            assertThat(tx(context, () -> call(context, "AppointmentMapper", "changeStatus", "E", "S", "A", 0L,
                    "CONFIRMED", "CHECKED_IN", "ACTOR", T0))).isEqualTo(0);
            assertThat(tx(context, () -> call(context, "AppointmentMapper", "markArrived", "E", "S", "A", 0L,
                    "CONFIRMED", T0, "ACTOR", T0))).isEqualTo(1);
            assertThat(tx(context, () -> call(context, "AppointmentMapper", "markArrived", "E", "S", "A", 1L,
                    "CHECKED_IN", T0, "ACTOR", T0))).isEqualTo(0);
            assertThat(sql.queryForObject("SELECT status FROM ord_appointment", String.class)).isEqualTo("CHECKED_IN");
            assertThat(sql.queryForObject("SELECT updated_by FROM ord_appointment", String.class)).isEqualTo("ACTOR");
        });
    }
    @Test
    void confirmationRequiresExistingHoldAndKnownStateTransition() {
        runner().run(context -> {
            sql.execute("UPDATE ord_appointment SET status='HELD',hold_no=NULL");
            assertThat(tx(context, () -> call(context, "AppointmentMapper", "changeStatus", "E", "S", "A", 0L,
                    "HELD", "CONFIRMED", "ACTOR", T0))).isEqualTo(0);
            sql.execute("UPDATE ord_appointment SET hold_no='H'");
            assertThat(tx(context, () -> call(context, "AppointmentMapper", "changeStatus", "E", "S", "A", 0L,
                    "HELD", "CONFIRMED", "ACTOR", T0))).isEqualTo(1);
        });
    }
    @Test
    void rollbackPreservesOriginalSchedule() {
        runner().run(context -> {
            assertThatThrownBy(() -> tx(context, () -> {
                assertThat(change(context, "S", 0, "CONFIRMED")).isEqualTo(1);
                throw new IllegalStateException("rollback");
            })).hasMessage("rollback");
            assertThat(sql.queryForObject("SELECT hold_no FROM ord_appointment", String.class)).isEqualTo("HOLD");
            assertThat(sql.queryForObject("SELECT version FROM ord_appointment", Long.class)).isZero();
        });
    }
    @Test
    void concurrentSameRevisionHasOnlyOneWinner() {
        runner().run(context -> {
            var start = new CountDownLatch(1);
            try (var workers = Executors.newFixedThreadPool(2)) {
                var futures = List.of(1, 2).stream().map(n -> workers.submit(() -> {
                    start.await();
                    return tx(context, () -> change(context, "S", 0, "CONFIRMED"));
                })).toList();
                start.countDown();
                int changed = 0;
                for (var future : futures) {
                    changed += future.get(15, TimeUnit.SECONDS);
                }
                assertThat(changed).isOne();
            }
        });
    }
    @Test
    void realMapperInsertUsesPrimaryAuditAndEntityScope() {
        runner().run(context -> {
            Object item = row("BtyAppointmentItem", "entityNo", "E", "appointmentItemNo", "AUDIT",
                    "appointmentNo", "A", "skuNo", "SKU", "definitionVersionNo", "DV", "qty", BigDecimal.ONE,
                    "uom", "COUNT", "sequenceNo", 1, "executionSnapshot", "{}");
            assertThat(tx(context, () -> mapper(context, "AppointmentItemMapper").insert(item))).isOne();
            assertThat(sql.queryForObject("SELECT created_by FROM ord_appointment_item "
                    + "WHERE appointment_item_no='AUDIT'", String.class)).isEqualTo("ACTOR");
            DataScopeContext.set(DataScopeSpec.of(ScopeDim.MERCHANT, Set.of("FOREIGN")));
            assertThat(mapper(context, "AppointmentItemMapper").selectList(null)).isEmpty();
        });
    }
}

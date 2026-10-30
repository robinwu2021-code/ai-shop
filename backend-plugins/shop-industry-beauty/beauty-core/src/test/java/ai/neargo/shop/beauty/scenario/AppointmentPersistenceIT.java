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
        initialize("BtyAppointment", "BtyAppointmentItem", "BtyAppointmentAssignment");
        appointment("A", "S");
        seed("bty_appointment_item", "appointment_item_no", "I", "appointment_no", "A", "sku_no", "SKU",
                "definition_version_no", "DV", "qty", 1, "uom", "COUNT", "sequence_no", 0,
                "requested_personnel_no", "REQUESTED", "execution_snapshot", "{}");
    }
    private int change(ApplicationContext context, String store, long version, String expected) {
        return (Integer) call(context, "AppointmentMapper", "changeSchedule", "E", store, "A", version, expected,
                T0.plusHours(2), T0.plusHours(3), "NEW-HOLD", "ACTOR", T0.plusMinutes(1));
    }
    private void assignment(long revision, String personnel) {
        seed("bty_appointment_assignment", "planned_assignment_no", "AS" + revision, "appointment_item_no", "I",
                "appointment_revision", revision, "requirement_no", "REQ", "personnel_no", personnel,
                "resource_no", "RESOURCE", "hold_no", "H" + revision, "start_at", T0, "end_at", T0.plusHours(1),
                "skill_snapshot", "{}");
    }
    @Test
    void keepsPreviousAssignmentRevisionAndRequestedPersonSeparate() {
        assignment(0, "FIRST");
        runner().run(context -> {
            assertThat(tx(context, () -> change(context, "S", 0, "CONFIRMED"))).isEqualTo(1);
            assignment(1, "NEXT");
            var before = (List<?>) call(context, "AppointmentAssignmentMapper", "selectByAppointmentRevision",
                    "E", "S", "A", 0L);
            var after = (List<?>) call(context, "AppointmentAssignmentMapper", "selectByAppointmentRevision",
                    "E", "S", "A", 1L);
            assertThat(before).hasSize(1);
            assertThat(after).hasSize(1);
            assertThat(sql.queryForObject("SELECT requested_personnel_no FROM bty_appointment_item", String.class))
                    .isEqualTo("REQUESTED");
            assertThat(sql.queryForList("SELECT personnel_no FROM bty_appointment_assignment "
                    + "ORDER BY appointment_revision", String.class)).containsExactly("FIRST", "NEXT");
        });
    }
    @Test
    void rejectsStaleScheduleAndWrongStoreOrTerminalState() {
        runner().run(context -> {
            assertThat(tx(context, () -> change(context, "OTHER", 0, "CONFIRMED"))).isEqualTo(0);
            assertThat(tx(context, () -> change(context, "S", 7, "CONFIRMED"))).isEqualTo(0);
            sql.execute("UPDATE bty_appointment SET status='CHECKED_IN'");
            assertThat(tx(context, () -> change(context, "S", 0, "CHECKED_IN"))).isEqualTo(0);
        });
    }
    @Test
    void limitsAssignmentsToAuthorizedUndeletedParentChain() {
        assignment(0, "FIRST");
        runner().run(context -> {
            assertThat((List<?>) call(context, "AppointmentAssignmentMapper", "selectByAppointmentRevision",
                    "E", "OTHER", "A", 0L)).isEmpty();
            sql.execute("UPDATE bty_appointment_item SET entity_no='FOREIGN'");
            assertThat((List<?>) call(context, "AppointmentAssignmentMapper", "selectByAppointmentRevision",
                    "E", "S", "A", 0L)).isEmpty();
            sql.execute("UPDATE bty_appointment_item SET entity_no='E',deleted=1");
            assertThat((List<?>) call(context, "AppointmentAssignmentMapper", "selectByAppointmentRevision",
                    "E", "S", "A", 0L)).isEmpty();
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
            assertThat(sql.queryForObject("SELECT status FROM bty_appointment", String.class)).isEqualTo("CHECKED_IN");
            assertThat(sql.queryForObject("SELECT updated_by FROM bty_appointment", String.class)).isEqualTo("ACTOR");
        });
    }
    @Test
    void confirmationRequiresExistingHoldAndKnownStateTransition() {
        runner().run(context -> {
            sql.execute("UPDATE bty_appointment SET status='HELD',hold_no=NULL");
            assertThat(tx(context, () -> call(context, "AppointmentMapper", "changeStatus", "E", "S", "A", 0L,
                    "HELD", "CONFIRMED", "ACTOR", T0))).isEqualTo(0);
            sql.execute("UPDATE bty_appointment SET hold_no='H'");
            assertThat(tx(context, () -> call(context, "AppointmentMapper", "changeStatus", "E", "S", "A", 0L,
                    "HELD", "CONFIRMED", "ACTOR", T0))).isEqualTo(1);
        });
    }
    @Test
    void rollbackPreservesOriginalScheduleAndAssignment() {
        assignment(0, "FIRST");
        runner().run(context -> {
            assertThatThrownBy(() -> tx(context, () -> {
                assertThat(change(context, "S", 0, "CONFIRMED")).isEqualTo(1);
                throw new IllegalStateException("rollback");
            })).hasMessage("rollback");
            assertThat(sql.queryForObject("SELECT hold_no FROM bty_appointment", String.class)).isEqualTo("HOLD");
            assertThat(sql.queryForObject("SELECT version FROM bty_appointment", Long.class)).isZero();
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
            assertThat(sql.queryForObject("SELECT created_by FROM bty_appointment_item "
                    + "WHERE appointment_item_no='AUDIT'", String.class)).isEqualTo("ACTOR");
            DataScopeContext.set(DataScopeSpec.of(ScopeDim.MERCHANT, Set.of("FOREIGN")));
            assertThat(mapper(context, "AppointmentItemMapper").selectList(null)).isEmpty();
        });
    }
}

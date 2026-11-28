package ai.neargo.shop.beauty.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.DuplicateKeyException;

/** 预约修订承担一次变更的生命周期（TDD §2.6 第 4–6 项，步 4-2）：持久化原语在真实 MySQL 上的语义。 */
class AppointmentRevisionPersistenceIT extends BeautyExecutionFixture {
    private static final String H = "h".repeat(64);

    @BeforeEach
    void database() throws Exception {
        initialize("BtyAppointmentArrangement");
        appointment("A", "S");
        draft("P1", "RESCHEDULE", 0L);
        draft("P2", "RESCHEDULE", 0L);
    }

    private void draft(String preview, String action, long basedOn) {
        seed("ord_appointment_revision", "preview_no", preview, "store_no", "S", "status", "DRAFT",
                "action", action, "based_on_revision", basedOn, "frozen_plan", "{}", "plan_hash", H,
                "expires_at", T0.plusMinutes(5), "actor_no", "ACTOR", "actor_realm", "MERCHANT");
    }

    private Object apply(ApplicationContext context, String preview, long revision, String command) {
        return tx(context, () -> call(context, "AppointmentArrangementMapper", "toApplying", "E", "S", preview, 0L,
                "A", revision, command, H, T0.plusSeconds(30), "ACTOR", T0));
    }

    private Object field(ApplicationContext context, String method, String key, String property) {
        Object row = tx(context, () -> call(context, "AppointmentArrangementMapper", method, "E", "S", key));
        return row == null ? null : new BeanWrapperImpl(row).getPropertyValue(property);
    }

    @Test
    void onlyOneChangeOfAnAppointmentIsInFlight() {
        runner().run(context -> {
            assertThat(apply(context, "P1", 1L, "C1")).isEqualTo(1);
            assertThatThrownBy(() -> apply(context, "P2", 1L, "C2")).isInstanceOf(DuplicateKeyException.class);
            assertThat(field(context, "selectInFlightForUpdate", "A", "previewNo")).isEqualTo("P1");
            assertThat(field(context, "selectByCommandForUpdate", "C1", "status")).isEqualTo("APPLYING");
            assertThat(field(context, "selectByPreviewForUpdate", "P2", "status")).isEqualTo("DRAFT");
        });
    }

    @Test
    void draftGoesInFlightOnlyAgainstTheVersionItWasBasedOnAndWhileOpen() {
        runner().run(context -> {
            sql.update("UPDATE ord_appointment SET version=1 WHERE appointment_no='A'");
            assertThat(apply(context, "P1", 1L, "C1")).isEqualTo(0); // 预约单已变
            sql.update("UPDATE ord_appointment SET version=0, status='CHECKED_IN' WHERE appointment_no='A'");
            assertThat(apply(context, "P1", 1L, "C1")).isEqualTo(0); // 已到店只能「到店后改」
            draft("P3", "RECEPTION_CHANGE", 0L);
            assertThat(apply(context, "P3", 1L, "C3")).isEqualTo(1);
        });
    }

    @Test
    void expiredDraftCannotGoInFlight() {
        sql.update("UPDATE ord_appointment_revision SET expires_at=? WHERE preview_no='P1'", T0);
        runner().run(context -> assertThat(apply(context, "P1", 1L, "C1")).isEqualTo(0));
    }

    @Test
    void permissionIsGrantedOnceAndConfirmationSupersedesTheOlderVersion() {
        seed("ord_appointment_revision", "appointment_no", "A", "arrangement_revision", 1L, "store_no", "S",
                "status", "CONFIRMED", "action", "CREATE", "based_on_revision", 0L);
        runner().run(context -> {
            assertThat(apply(context, "P1", 2L, "C1")).isEqualTo(1);
            Object permit = tx(context, () -> call(context, "AppointmentArrangementMapper", "permit", "E", "S", "C1",
                    1L, "D1", T0, T0.plusMinutes(10), "ACTOR"));
            assertThat(permit).isEqualTo(1);
            assertThat(tx(context, () -> call(context, "AppointmentArrangementMapper", "permit", "E", "S", "C1",
                    2L, "D2", T0, T0.plusMinutes(10), "ACTOR"))).isEqualTo(0);
            assertThat(tx(context, () -> call(context, "AppointmentArrangementMapper", "refuse", "E", "S", "C1",
                    2L, "D3", "LATE", "ACTOR", T0))).isEqualTo(0);
            assertThat(field(context, "selectByCommandForUpdate", "C1", "decisionNo")).isEqualTo("D1");
            assertThat(settle(context, 2L, "CONFIRMED")).isEqualTo(1);
            assertThat(tx(context, () -> call(context, "AppointmentArrangementMapper", "supersedeBefore", "E", "S",
                    "A", 2L, "ACTOR", T0))).isEqualTo(1);
            assertThat(sql.queryForList("SELECT status FROM ord_appointment_revision WHERE appointment_no='A' "
                    + "ORDER BY arrangement_revision", String.class)).containsExactly("SUPERSEDED", "CONFIRMED");
            assertThat(field(context, "selectInFlightForUpdate", "A", "previewNo")).isNull();
        });
    }

    @Test
    void refusedChangeNeverConfirmsAndReleaseEndsRejected() {
        runner().run(context -> {
            assertThat(apply(context, "P1", 1L, "C1")).isEqualTo(1);
            assertThat(tx(context, () -> call(context, "AppointmentArrangementMapper", "refuse", "E", "S", "C1",
                    1L, "D1", "OWNER_REJECTED_BEFORE_PERMISSION", "ACTOR", T0))).isEqualTo(1);
            assertThat(settle(context, 2L, "CONFIRMED")).isEqualTo(0);
            assertThat(settle(context, 2L, "RELEASING")).isEqualTo(1);
            // 待释放仍占着在途闸门
            assertThatThrownBy(() -> apply(context, "P2", 1L, "C2")).isInstanceOf(DuplicateKeyException.class);
            assertThat(settle(context, 3L, "REJECTED")).isEqualTo(1);
            assertThat(field(context, "selectByCommandForUpdate", "C1", "rejectCode"))
                    .isEqualTo("OWNER_REJECTED_BEFORE_PERMISSION");
            // 被拒的版本保留修订号 1，下一次变更取 2
            Object next = tx(context, () -> call(context, "AppointmentArrangementMapper", "nextRevision", "E", "S", "A"));
            assertThat(next).isEqualTo(2L);
            assertThat(apply(context, "P2", 2L, "C2")).isEqualTo(1);
        });
    }

    @Test
    void recoveryClaimsOnlyDueInFlightRevisions() {
        runner().run(context -> {
            assertThat(apply(context, "P1", 1L, "C1")).isEqualTo(1); // next_retry_at = T0+30s
            List<?> early = tx(context, () -> (List<?>) call(context, "AppointmentArrangementMapper", "claimDue",
                    T0, 10));
            assertThat(early).isEmpty();
            List<?> due = tx(context, () -> (List<?>) call(context, "AppointmentArrangementMapper", "claimDue",
                    T0.plusMinutes(1), 10));
            assertThat(due).hasSize(1);
            assertThat(new BeanWrapperImpl(due.getFirst()).getPropertyValue("commandNo")).isEqualTo("C1");
        });
    }

    private Object settle(ApplicationContext context, long version, String target) {
        return tx(context, () -> call(context, "AppointmentArrangementMapper", "settle", "E", "S", "C1", version,
                target, H, target.equals("CONFIRMED") ? null : "RESOURCE_REJECTED",
                target.equals("RELEASING") ? T0.plusSeconds(30) : null, "ACTOR", T0));
    }
}

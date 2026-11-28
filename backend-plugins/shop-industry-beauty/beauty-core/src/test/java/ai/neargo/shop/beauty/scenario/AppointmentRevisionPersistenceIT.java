package ai.neargo.shop.beauty.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.DuplicateKeyException;

/**
 * 预约修订与资源命令（TDD §2.6 第 4–6 项方向 A）：持久化原语在真实 MySQL 上的语义。
 * 修订管一次变更（草稿、在途闸门、生效与拒绝），命令一条一行管许可与对账。
 */
class AppointmentRevisionPersistenceIT extends BeautyExecutionFixture {
    private static final String H = "h".repeat(64);

    @BeforeEach
    void database() throws Exception {
        initialize("BtyAppointmentArrangement", "BtyAppointmentCommand");
        appointment("A", "S");
        draft("P1", 0L);
        draft("P2", 0L);
    }

    private void draft(String preview, long basedOn) {
        seed("ord_appointment_revision", "preview_no", preview, "store_no", "S", "status", "DRAFT",
                "based_on_revision", basedOn, "frozen_plan", "{}", "plan_hash", H,
                "expires_at", T0.plusMinutes(5), "actor_no", "ACTOR", "actor_realm", "MERCHANT");
    }

    private Object apply(ApplicationContext context, String preview, long revision, String action, String change) {
        return tx(context, () -> call(context, "AppointmentArrangementMapper", "toApplying", "E", "S", preview, 0L,
                "A", revision, action, change, T0.plusSeconds(30), 0L, "ACTOR", T0)); // 调用者核对过的预约单版本 0
    }

    private Object revision(ApplicationContext context, String method, String key, String property) {
        Object row = tx(context, () -> call(context, "AppointmentArrangementMapper", method, "E", "S", key));
        return row == null ? null : new BeanWrapperImpl(row).getPropertyValue(property);
    }

    private Object settleChange(ApplicationContext context, String change, long version, String target, String code) {
        return tx(context, () -> call(context, "AppointmentArrangementMapper", "settle", "E", "S", change, version,
                target, code, target.equals("RELEASING") ? T0.plusSeconds(30) : null, "ACTOR", T0));
    }

    @Test
    void onlyOneChangeOfAnAppointmentIsInFlight() {
        runner().run(context -> {
            assertThat(apply(context, "P1", 1L, "CHANGE", "CH1")).isEqualTo(1);
            assertThatThrownBy(() -> apply(context, "P2", 1L, "CHANGE", "CH2"))
                    .isInstanceOf(DuplicateKeyException.class);
            assertThat(revision(context, "selectInFlightForUpdate", "A", "previewNo")).isEqualTo("P1");
            assertThat(revision(context, "selectByChangeForUpdate", "CH1", "status")).isEqualTo("APPLYING");
            assertThat(revision(context, "selectByPreviewForUpdate", "P2", "status")).isEqualTo("DRAFT");
        });
    }

    @Test
    void draftGoesInFlightOnlyAgainstTheVersionItWasBasedOnAndWhileOpen() {
        runner().run(context -> {
            sql.update("UPDATE ord_appointment SET version=1 WHERE appointment_no='A'");
            assertThat(apply(context, "P1", 1L, "CHANGE", "CH1")).isEqualTo(0); // 预约单已变
            sql.update("UPDATE ord_appointment SET version=0, status='CHECKED_IN' WHERE appointment_no='A'");
            assertThat(apply(context, "P1", 1L, "CHANGE", "CH1")).isEqualTo(0); // 已到店只能「到店后改」
            assertThat(apply(context, "P1", 1L, "RECEPTION_CHANGE", "CH1")).isEqualTo(1);
        });
    }

    @Test
    void expiredDraftCannotGoInFlightButCancellationIgnoresExpiry() {
        sql.update("UPDATE ord_appointment_revision SET expires_at=? WHERE preview_no IN ('P1','P2')", T0);
        runner().run(context -> {
            assertThat(apply(context, "P1", 1L, "CHANGE", "CH1")).isEqualTo(0);
            assertThat(apply(context, "P2", 1L, "CANCEL", "CH2")).isEqualTo(1);
        });
    }

    @Test
    void confirmationSupersedesTheOlderVersionAndFreesTheGate() {
        seed("ord_appointment_revision", "appointment_no", "A", "arrangement_revision", 1L, "store_no", "S",
                "status", "CONFIRMED", "action", "CREATE", "based_on_revision", 0L, "change_no", "CH0");
        runner().run(context -> {
            assertThat(apply(context, "P1", 2L, "CHANGE", "CH1")).isEqualTo(1);
            assertThat(settleChange(context, "CH1", 1L, "CONFIRMED", null)).isEqualTo(1);
            assertThat(tx(context, () -> call(context, "AppointmentArrangementMapper", "supersedeBefore", "E", "S",
                    "A", 2L, "ACTOR", T0))).isEqualTo(1);
            assertThat(sql.queryForList("SELECT status FROM ord_appointment_revision WHERE appointment_no='A' "
                    + "AND arrangement_revision IS NOT NULL ORDER BY arrangement_revision", String.class))
                    .containsExactly("SUPERSEDED", "CONFIRMED");
            assertThat(revision(context, "selectInFlightForUpdate", "A", "previewNo")).isNull();
        });
    }

    @Test
    void releasingKeepsTheGateAndRejectedRevisionKeepsItsNumber() {
        runner().run(context -> {
            assertThat(apply(context, "P1", 1L, "CHANGE", "CH1")).isEqualTo(1);
            assertThat(settleChange(context, "CH1", 1L, "RELEASING", null)).isEqualTo(0); // 拒绝要有原因码
            assertThat(settleChange(context, "CH1", 1L, "RELEASING", "CONFIRM_FAILED")).isEqualTo(1);
            assertThatThrownBy(() -> apply(context, "P2", 1L, "CHANGE", "CH2"))
                    .isInstanceOf(DuplicateKeyException.class); // 待释放仍占着在途闸门
            assertThat(settleChange(context, "CH1", 2L, "CONFIRMED", null)).isEqualTo(0);
            assertThat(settleChange(context, "CH1", 2L, "REJECTED", null)).isEqualTo(1);
            Object next = tx(context, () -> call(context, "AppointmentArrangementMapper", "nextRevision", "E", "S", "A"));
            assertThat(next).isEqualTo(2L); // 被拒的版本保留修订号 1
            assertThat(apply(context, "P2", 2L, "CHANGE", "CH2")).isEqualTo(1);
        });
    }

    @Test
    void recoveryClaimsOnlyDueInFlightRevisions() {
        runner().run(context -> {
            assertThat(apply(context, "P1", 1L, "CHANGE", "CH1")).isEqualTo(1); // next_retry_at = T0+30s
            List<?> early = tx(context, () -> (List<?>) call(context, "AppointmentArrangementMapper", "claimDue",
                    T0, 10));
            assertThat(early).isEmpty();
            List<?> due = tx(context, () -> (List<?>) call(context, "AppointmentArrangementMapper", "claimDue",
                    T0.plusMinutes(1), 10));
            assertThat(due).hasSize(1);
            assertThat(new BeanWrapperImpl(due.getFirst()).getPropertyValue("changeNo")).isEqualTo("CH1");
        });
    }

    @Test
    void inFlightChangeBlocksScheduleCancelConfirmAndArrival() {
        // 原资源围栏的语义改由修订的在途闸门承担（4-4b-2）：有变更在途时预约单的改期、取消、确认、到店都不成
        runner().run(ctx -> {
            assertThat(apply(ctx, "P1", 1L, "CHANGE", "CH1")).isEqualTo(1);
            assertThat(tx(ctx, () -> call(ctx, "AppointmentMapper", "changeSchedule", "E", "S", "A", 0L,
                    "CONFIRMED", T0.plusHours(2), T0.plusHours(3), "NEXT", "ACTOR", T0))).isEqualTo(0);
            assertThat(tx(ctx, () -> call(ctx, "AppointmentMapper", "changeStatus", "E", "S", "A", 0L,
                    "CONFIRMED", "CANCELLED", "ACTOR", T0))).isEqualTo(0);
            assertThat(tx(ctx, () -> call(ctx, "AppointmentMapper", "markArrived", "E", "S", "A", 0L,
                    "CONFIRMED", T0, "ACTOR", T0))).isEqualTo(0);
            sql.update("UPDATE ord_appointment SET status='HELD'");
            assertThat(tx(ctx, () -> call(ctx, "AppointmentMapper", "changeStatus", "E", "S", "A", 0L,
                    "HELD", "CONFIRMED", "ACTOR", T0))).isEqualTo(0);
            assertThat(settleChange(ctx, "CH1", 1L, "REJECTED", "RESOURCE_REJECTED")).isEqualTo(1); // 放开闸门
            assertThat(tx(ctx, () -> call(ctx, "AppointmentMapper", "changeStatus", "E", "S", "A", 0L,
                    "HELD", "CANCELLED", "ACTOR", T0))).isEqualTo(1);
        });
    }

    // ---------- 资源命令 ----------

    private void command(String key, String action) {
        seed("ord_appointment_command", "store_no", "S", "appointment_no", "A", "change_no", "CH1",
                "request_key", key, "action", action, "source_revision", 1L, "accept_payload", "{}",
                "status", "SENT");
    }

    private Object bind(ApplicationContext context, String key, String commandNo) {
        return tx(context, () -> call(context, "AppointmentCommandMapper", "bind", "E", "S", key, 0L, commandNo, H, H,
                "{}", "ACTOR", T0));
    }

    private Object cmd(ApplicationContext context, String commandNo, String property) {
        Object row = tx(context, () -> call(context, "AppointmentCommandMapper", "selectByCommandForUpdate", "E", "S",
                commandNo));
        return row == null ? null : new BeanWrapperImpl(row).getPropertyValue(property);
    }

    private Object settleCommand(ApplicationContext context, String commandNo, long version, String target) {
        return tx(context, () -> call(context, "AppointmentCommandMapper", "settle", "E", "S", commandNo, version,
                target, H, target.equals("SUCCEEDED") ? null : "RESOURCE_REJECTED", "ACTOR", T0));
    }

    @Test
    void eachCommandOfAChangeIsItsOwnRowAndKeepsItsPermission() {
        command("CH1:RESERVE", "RESERVE");
        command("CH1:CONFIRM", "CONFIRM");
        assertThatThrownBy(() -> command("CH1:RESERVE", "RESERVE")).isInstanceOf(DuplicateKeyException.class);
        runner().run(context -> {
            assertThat(bind(context, "CH1:RESERVE", "HC1")).isEqualTo(1);
            assertThat(bind(context, "CH1:RESERVE", "HC1")).isEqualTo(0); // 已绑定
            Object permit = tx(context, () -> call(context, "AppointmentCommandMapper", "permit", "E", "S", "HC1", 1L,
                    "D1", T0, T0.plusMinutes(2), "A", 1L, "ACTOR"));
            assertThat(permit).isEqualTo(1);
            assertThat(tx(context, () -> call(context, "AppointmentCommandMapper", "permit", "E", "S", "HC1", 2L,
                    "D2", T0, T0.plusMinutes(2), "A", 1L, "ACTOR"))).isEqualTo(0); // 只许一次
            assertThat(settleCommand(context, "HC1", 2L, "SUCCEEDED")).isEqualTo(1);
            // 下一条命令另起一行，第一条的许可原样留着，重放能答回
            assertThat(bind(context, "CH1:CONFIRM", "HC2")).isEqualTo(1);
            assertThat(cmd(context, "HC1", "decisionNo")).isEqualTo("D1");
            assertThat(cmd(context, "HC1", "fenceNo")).isEqualTo("A");
            List<?> all = tx(context, () -> (List<?>) call(context, "AppointmentCommandMapper", "selectByChange", "E",
                    "S", "CH1"));
            assertThat(all).extracting(row -> new BeanWrapperImpl(row).getPropertyValue("action"))
                    .containsExactly("RESERVE", "CONFIRM");
        });
    }

    @Test
    void refusedCommandCanOnlyEndRejected() {
        command("CH1:REPLACE", "REPLACE");
        runner().run(context -> {
            assertThat(bind(context, "CH1:REPLACE", "HC1")).isEqualTo(1);
            assertThat(tx(context, () -> call(context, "AppointmentCommandMapper", "refuse", "E", "S", "HC1", 1L,
                    "D1", "OWNER_REJECTED_BEFORE_PERMISSION", "ACTOR", T0))).isEqualTo(1);
            assertThat(tx(context, () -> call(context, "AppointmentCommandMapper", "permit", "E", "S", "HC1", 2L,
                    "D2", T0, T0.plusMinutes(2), "A", 1L, "ACTOR"))).isEqualTo(0);
            assertThat(settleCommand(context, "HC1", 2L, "SUCCEEDED")).isEqualTo(0);
            assertThat(settleCommand(context, "HC1", 2L, "REJECTED")).isEqualTo(1);
            assertThat(settleCommand(context, "HC1", 3L, "REJECTED")).isEqualTo(0); // 已对账不再改
            assertThat(cmd(context, "HC1", "rejectCode")).isEqualTo("OWNER_REJECTED_BEFORE_PERMISSION");
        });
    }

    @Test
    void unboundCommandCannotBePermitted() {
        command("CH1:RELEASE", "RELEASE");
        runner().run(context -> assertThat(tx(context, () -> call(context, "AppointmentCommandMapper", "permit", "E",
                "S", "NONE", 0L, "D1", T0, T0.plusMinutes(2), "A", 1L, "ACTOR"))).isEqualTo(0));
    }
}

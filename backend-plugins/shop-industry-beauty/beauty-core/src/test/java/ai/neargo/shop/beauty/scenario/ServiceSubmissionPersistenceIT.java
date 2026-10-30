package ai.neargo.shop.beauty.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.DuplicateKeyException;

class ServiceSubmissionPersistenceIT extends BeautyExecutionFixture {
    @BeforeEach
    void database() throws Exception {
        initialize("BtyServiceSubmission");
        submission("SUB", "OPEN", "S", "RECEIVED");
    }

    private void submission(String number, String action, String store, String status) {
        seed("bty_service_submission", "submission_no", number, "store_no", store, "action", action,
                "request_key_hash", "a".repeat(64), "payload_hash", "b".repeat(64), "status", status,
                "frozen_command", "{\"originalSourceNo\":\"ORDER\"}", "next_retry_at", T0);
    }

    private Object advance(ApplicationContext context, long version, String before, String after) {
        return call(context, "ServiceSubmissionMapper", "advanceProgress", "E", "S", "SUB", version,
                before, after, after.equals("BOUND") || after.equals("REJECTED") ? null : T0.plusHours(1),
                after.equals("REJECTED") ? "SOURCE_DECLINED" : null, "ACTOR", T0);
    }

    private List<?> due(ApplicationContext context, String store, int limit) {
        return (List<?>) call(context, "ServiceSubmissionMapper", "selectDueForUpdate", "E", store, T0, limit);
    }

    @Test
    void sameRequestReturnsOriginalPayloadAndFrozenSourceCommand() {
        runner().run(context -> {
            Object intent = call(context, "ServiceSubmissionMapper", "selectByRequestKey", "E", "S", "a".repeat(64));
            var fields = new BeanWrapperImpl(intent);
            assertThat(fields.getPropertyValue("payloadHash")).isEqualTo("b".repeat(64));
            assertThat((String) fields.getPropertyValue("frozenCommand")).contains("ORDER");
            assertThat(call(context, "ServiceSubmissionMapper", "selectByRequestKey", "E", "OTHER", "a".repeat(64)))
                    .isNull();
            Object changed = row("BtyServiceSubmission", "entityNo", "E", "submissionNo", "DUP", "storeNo", "S",
                    "action", "OPEN", "requestKeyHash", "a".repeat(64), "payloadHash", "c".repeat(64),
                    "status", "RECEIVED", "frozenCommand", "{\"changed\":true}");
            assertThatThrownBy(() -> tx(context, () -> mapper(context, "ServiceSubmissionMapper").insert(changed)))
                    .isInstanceOf(DuplicateKeyException.class);
            Object original = call(context, "ServiceSubmissionMapper", "selectByRequestKey", "E", "S", "a".repeat(64));
            assertThat(new BeanWrapperImpl(original).getPropertyValue("payloadHash")).isEqualTo("b".repeat(64));
        });
    }

    @Test
    void rejectsAmbiguousRequestHashAcrossActionsInsteadOfPickingOne() {
        submission("OTHER", "ADD", "S", "RECEIVED");
        runner().run(context -> assertThatThrownBy(() -> call(context, "ServiceSubmissionMapper", "selectByRequestKey",
                "E", "S", "a".repeat(64))).hasStackTraceContaining("TooManyResultsException"));
    }

    @Test
    void progressCasRetainsOriginalIntentAndRejectsTerminalRestart() {
        runner().run(context -> {
            assertThat(tx(context, () -> advance(context, 0, "RECEIVED", "BOUND"))).isEqualTo(0);
            assertThat(tx(context, () -> advance(context, 0, "RECEIVED", "SOURCE_PENDING"))).isEqualTo(1);
            assertThat(tx(context, () -> advance(context, 0, "SOURCE_PENDING", "RECOVERING"))).isEqualTo(0);
            assertThat(tx(context, () -> advance(context, 1, "SOURCE_PENDING", "RECOVERING"))).isEqualTo(1);
            assertThat(tx(context, () -> advance(context, 2, "RECOVERING", "BOUND"))).isEqualTo(1);
            assertThat(tx(context, () -> advance(context, 3, "BOUND", "SOURCE_PENDING"))).isEqualTo(0);
            assertThat(sql.queryForObject("SELECT next_retry_at FROM bty_service_submission", Object.class)).isNull();
            assertThat(sql.queryForObject("SELECT updated_by FROM bty_service_submission", String.class))
                    .isEqualTo("ACTOR");
            assertThat(sql.queryForObject("SELECT frozen_command FROM bty_service_submission", String.class))
                    .contains("ORDER");
        });
    }

    @Test
    void activeLeaseNeedsFutureRetryAndRejectedNeedsFailureCode() {
        runner().run(context -> {
            assertThat(tx(context, () -> call(context, "ServiceSubmissionMapper", "advanceProgress", "E", "S", "SUB",
                    0L, "RECEIVED", "RECEIVED", T0, null, "ACTOR", T0))).isEqualTo(0);
            assertThat(tx(context, () -> call(context, "ServiceSubmissionMapper", "advanceProgress", "E", "S", "SUB",
                    0L, "RECEIVED", "REJECTED", null, null, "ACTOR", T0))).isEqualTo(0);
            assertThat(tx(context, () -> advance(context, 0, "RECEIVED", "REJECTED"))).isEqualTo(1);
            assertThat(tx(context, () -> advance(context, 1, "REJECTED", "SOURCE_PENDING"))).isEqualTo(0);
        });
    }

    @Test
    void dueRowsAreBoundedScopedAndLeasedInSameTransaction() {
        submission("FUTURE", "ADD", "S", "SOURCE_PENDING");
        sql.execute("UPDATE bty_service_submission SET next_retry_at=DATE_ADD(next_retry_at,INTERVAL 1 HOUR) "
                + "WHERE submission_no='FUTURE'");
        submission("TERMINAL", "BIND", "S", "BOUND");
        submission("STORE", "OPEN", "OTHER", "RECEIVED");
        runner().run(context -> {
            assertThat(tx(context, () -> due(context, "S", 0))).isEmpty();
            assertThat(tx(context, () -> due(context, "S", -1))).isEmpty();
            assertThat(tx(context, () -> due(context, "S", 101))).isEmpty();
            tx(context, () -> {
                assertThat(due(context, "S", 1)).hasSize(1);
                assertThat(advance(context, 0, "RECEIVED", "RECEIVED")).isEqualTo(1);
                return null;
            });
            assertThat(tx(context, () -> due(context, "S", 100))).isEmpty();
            assertThat(tx(context, () -> due(context, "OTHER", 100))).hasSize(1);
        });
    }

    @Test
    void concurrentRecoverySkipsLockedIntent() {
        submission("SECOND", "ADD", "S", "RECOVERING");
        runner().run(context -> {
            var locked = new CountDownLatch(1);
            var scanned = new CountDownLatch(1);
            try (var workers = Executors.newFixedThreadPool(2)) {
                var owner = workers.submit(() -> tx(context, () -> {
                    assertThat(call(context, "ServiceSubmissionMapper", "selectScopedForUpdate", "E", "S", "SUB"))
                            .isNotNull();
                    locked.countDown();
                    await(scanned);
                    return null;
                }));
                var recovery = workers.submit(() -> {
                    await(locked);
                    try {
                        return tx(context, () -> {
                            var rows = due(context, "S", 10);
                            assertThat(rows).hasSize(1);
                            return new BeanWrapperImpl(rows.getFirst()).getPropertyValue("submissionNo");
                        });
                    } finally {
                        scanned.countDown();
                    }
                });
                assertThat(recovery.get(15, TimeUnit.SECONDS)).isEqualTo("SECOND");
                owner.get(15, TimeUnit.SECONDS);
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
    void optionalWorkOrderMustBelongToSameEntityAndStore() {
        workOrder("W", "OTHER");
        sql.execute("UPDATE bty_service_submission SET work_order_no='W'");
        runner().run(context -> {
            assertThat(tx(context, () -> call(context, "ServiceSubmissionMapper", "selectScopedForUpdate",
                    "E", "S", "SUB"))).isNull();
            assertThat(tx(context, () -> advance(context, 0, "RECEIVED", "SOURCE_PENDING"))).isEqualTo(0);
            assertThat(tx(context, () -> due(context, "S", 100))).isEmpty();
        });
    }

    @Test
    void actualEntityInsertUsesPrimaryAudit() {
        runner().run(context -> {
            Object intent = row("BtyServiceSubmission", "entityNo", "E", "submissionNo", "NEW", "storeNo", "S",
                    "action", "ADD", "requestKeyHash", "c".repeat(64), "payloadHash", "d".repeat(64),
                    "status", "RECEIVED", "frozenCommand", "{}");
            assertThat(tx(context, () -> mapper(context, "ServiceSubmissionMapper").insert(intent))).isOne();
            assertThat(sql.queryForObject("SELECT created_by FROM bty_service_submission WHERE submission_no='NEW'",
                    String.class)).isEqualTo("ACTOR");
        });
    }
    @Test
    void leaseMustRemainFutureAtPersistedMillisecondPrecision() {
        runner().run(context -> {
            assertThat(tx(context, () -> call(context, "ServiceSubmissionMapper", "advanceProgress", "E", "S", "SUB",
                    0L, "RECEIVED", "RECEIVED", T0.plusNanos(100_000), null, "ACTOR", T0))).isEqualTo(0);
            assertThat(tx(context, () -> call(context, "ServiceSubmissionMapper", "advanceProgress", "E", "S", "SUB",
                    0L, "RECEIVED", "RECEIVED", T0.plusNanos(1_000_000), null, "ACTOR", T0))).isEqualTo(1);
            Object intent = tx(context, () -> call(context, "ServiceSubmissionMapper", "selectScopedForUpdate",
                    "E", "S", "SUB"));
            assertThat(new BeanWrapperImpl(intent).getPropertyValue("nextRetryAt"))
                    .isEqualTo(T0.plusNanos(1_000_000));
        });
    }

}

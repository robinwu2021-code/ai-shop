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
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.DuplicateKeyException;

class SourceAllocationConcurrencyIT extends BeautyExecutionFixture {
    @BeforeEach
    void database() throws Exception {
        initialize("BtySourceAllocationGuard", "BtyTradeBinding", "BtyServiceSubmission");
        seed("bty_source_allocation_guard", "guard_no", "G", "source_kind", "STORE_ORDER", "source_no", "ORDER",
                "source_line_ref", "LINE", "source_revision", 1L, "source_qty", 2, "allocated_qty", 0,
                "uom", "COUNT", "source_hash", "a".repeat(64));
    }

    private Object allocate(ApplicationContext context, long version, long sourceRevision, String qty) {
        return call(context, "SourceAllocationGuardMapper", "allocateQuantity", "E", "G", version,
                sourceRevision, new BigDecimal(qty), "ACTOR", T0);
    }

    private Object release(ApplicationContext context, long version, String qty) {
        return call(context, "SourceAllocationGuardMapper", "releaseUnconsumedQuantity", "E", "G", version,
                new BigDecimal(qty), "ACTOR", T0);
    }

    private void referencedAllocation() {
        sql.execute("UPDATE bty_source_allocation_guard SET allocated_qty=2");
        workOrder("W", "S");
        task("T", "W");
        seed("bty_service_submission", "submission_no", "SUB", "store_no", "S", "work_order_no", "W",
                "action", "OPEN", "request_key_hash", "a".repeat(64), "payload_hash", "b".repeat(64),
                "status", "BOUND", "frozen_command", "{}");
        binding("B", 1);
    }

    private void binding(String number, long revision) {
        seed("bty_trade_binding", "binding_no", number, "submission_no", "SUB", "task_no", "T",
                "binding_revision", revision, "source_kind", "STORE_ORDER", "source_no", "ORDER",
                "source_line_ref", "LINE", "source_revision", 1L, "qty", 2, "uom", "COUNT",
                "source_snapshot_hash", "a".repeat(64), "guard_no", "G");
    }

    @Test
    void allocatesOnlyWithinOriginalQuantityAndExactRevision() {
        runner().run(context -> {
            assertThat(tx(context, () -> allocate(context, 0, 1, "0"))).isEqualTo(0);
            assertThat(tx(context, () -> allocate(context, 0, 1, "0.0000001"))).isEqualTo(0);
            assertThat(tx(context, () -> allocate(context, 0, 2, "1"))).isEqualTo(0);
            assertThat(tx(context, () -> allocate(context, 0, 1, "3"))).isEqualTo(0);
            assertThat(tx(context, () -> allocate(context, 0, 1, "1.5"))).isEqualTo(1);
            assertThat(tx(context, () -> allocate(context, 0, 1, "0.5"))).isEqualTo(0);
            assertThat(tx(context, () -> allocate(context, 1, 1, "0.5"))).isEqualTo(1);
            assertThat(sql.queryForObject("SELECT allocated_qty FROM bty_source_allocation_guard", BigDecimal.class))
                    .isEqualByComparingTo("2");
            assertThat(sql.queryForObject("SELECT updated_by FROM bty_source_allocation_guard", String.class))
                    .isEqualTo("ACTOR");
        });
    }

    @Test
    void concurrentTasksShareOneGuardAndCannotExceedSourceQuantity() {
        workOrder("W1", "S");
        workOrder("W2", "OTHER");
        task("T1", "W1");
        task("T2", "W2");
        runner().run(context -> {
            var start = new CountDownLatch(1);
            try (var workers = Executors.newFixedThreadPool(2)) {
                var futures = List.of("T1", "T2").stream().map(task -> workers.submit(() -> {
                    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                    return tx(context, () -> {
                        Object guard = call(context, "SourceAllocationGuardMapper", "selectSourceForUpdate",
                                "E", "STORE_ORDER", "ORDER", "LINE");
                        assertThat(guard).isNotNull();
                        long version = (Long) new BeanWrapperImpl(guard).getPropertyValue("version");
                        return (Integer) allocate(context, version, 1, "1.5");
                    });
                })).toList();
                start.countDown();
                int winners = 0;
                for (var future : futures) {
                    winners += future.get(15, TimeUnit.SECONDS);
                }
                assertThat(winners).isOne();
                assertThat(sql.queryForObject("SELECT allocated_qty FROM bty_source_allocation_guard",
                        BigDecimal.class)).isEqualByComparingTo("1.5");
            }
        });
    }

    @Test
    void sourceRevisionsCannotCreateAnotherGuardForSameCanonicalLine() {
        assertThatThrownBy(() -> seed("bty_source_allocation_guard", "guard_no", "G2", "source_kind", "STORE_ORDER",
                "source_no", "ORDER", "source_line_ref", "LINE", "source_revision", 2L, "source_qty", 2,
                "allocated_qty", 0, "uom", "COUNT", "source_hash", "b".repeat(64)))
                .isInstanceOf(DuplicateKeyException.class);
        runner().run(context -> assertThat(tx(context, () -> call(context, "SourceAllocationGuardMapper",
                "selectSourceForUpdate", "FOREIGN", "STORE_ORDER", "ORDER", "LINE"))).isNull());
    }

    @Test
    void refreshPreservesAllocatedQuantityAndRequiresNewerCompatibleSnapshot() {
        sql.execute("UPDATE bty_source_allocation_guard SET allocated_qty=1");
        runner().run(context -> {
            assertThat(tx(context, () -> refresh(context, 1, "2", "b".repeat(64)))).isEqualTo(0);
            assertThat(tx(context, () -> refresh(context, 2, "0.5", "b".repeat(64)))).isEqualTo(0);
            assertThat(tx(context, () -> refresh(context, 2, "3", "short"))).isEqualTo(0);
            assertThat(tx(context, () -> refresh(context, 2, "3", "b".repeat(64)))).isEqualTo(1);
            assertThat(tx(context, () -> allocate(context, 1, 1, "1"))).isEqualTo(0);
            assertThat(tx(context, () -> allocate(context, 1, 2, "2"))).isEqualTo(1);
        });
    }

    private Object refresh(ApplicationContext context, long revision, String qty, String hash) {
        return call(context, "SourceAllocationGuardMapper", "refreshSourceSnapshot", "E", "G", 0L, 1L,
                revision, new BigDecimal(qty), hash, "ACTOR", T0);
    }

    @Test
    void releasesOnlyPositiveRepresentableUnreferencedReservation() {
        sql.execute("UPDATE bty_source_allocation_guard SET allocated_qty=2");
        runner().run(context -> {
            assertThat(tx(context, () -> release(context, 0, "0.0000001"))).isEqualTo(0);
            assertThat(tx(context, () -> release(context, 0, "3"))).isEqualTo(0);
            assertThat(tx(context, () -> release(context, 0, "1"))).isEqualTo(1);
            assertThat(tx(context, () -> release(context, 0, "1"))).isEqualTo(0);
            assertThat(tx(context, () -> release(context, 1, "1"))).isEqualTo(1);
        });
    }

    @Test
    void doesNotReleaseConsumedQuantityEvenWhenHistoricalBindingWasSoftDeleted() {
        referencedAllocation();
        sql.execute("UPDATE bty_service_task SET status='DONE',done_qty=2,finished_at=started_at");
        sql.execute("UPDATE bty_trade_binding SET deleted=1");
        runner().run(context -> assertThat(tx(context, () -> release(context, 0, "1"))).isEqualTo(0));
    }

    @Test
    void doesNotReleaseAllocationReferencedByRecoveryInFlight() {
        referencedAllocation();
        sql.execute("UPDATE bty_service_task SET status='VOIDED',started_at=NULL,voided_qty=qty");
        sql.execute("UPDATE bty_service_submission SET status='RECOVERING'");
        runner().run(context -> assertThat(tx(context, () -> release(context, 0, "1"))).isEqualTo(0));
    }

    @Test
    void releasesFullyVoidedNeverStartedTaskWithoutDeletingBindingHistory() {
        referencedAllocation();
        sql.execute("UPDATE bty_service_task SET status='VOIDED',started_at=NULL,voided_qty=qty");
        runner().run(context -> {
            assertThat(tx(context, () -> release(context, 0, "2"))).isEqualTo(1);
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM bty_trade_binding", Integer.class)).isOne();
        });
    }

    @Test
    void malformedParentChainCannotAuthorizeRelease() {
        referencedAllocation();
        sql.execute("UPDATE bty_service_task SET status='VOIDED',started_at=NULL,voided_qty=qty");
        sql.execute("UPDATE bty_service_submission SET store_no='OTHER'");
        runner().run(context -> assertThat(tx(context, () -> release(context, 0, "1"))).isEqualTo(0));
    }

    @Test
    void batchReadsOnlyRequestedBindingRevisionAndAuthorizedDoubleParent() {
        referencedAllocation();
        binding("B2", 2);
        runner().run(context -> {
            assertThat(bindings(context, "S", List.of("T"), 1)).hasSize(1);
            assertThat(bindings(context, "S", List.of("T"), 2)).hasSize(1);
            assertThat(bindings(context, "OTHER", List.of("T"), 1)).isEmpty();
            assertThat(bindings(context, "S", List.of(), 1)).isEmpty();
            assertThat(bindings(context, "S", null, 1)).isEmpty();
            sql.execute("UPDATE bty_service_submission SET entity_no='FOREIGN'");
            assertThat(bindings(context, "S", List.of("T"), 1)).isEmpty();
        });
    }

    private List<?> bindings(ApplicationContext context, String store, List<String> tasks, long revision) {
        return (List<?>) call(context, "TradeBindingMapper", "selectByTaskRevisions", "E", store, tasks, revision);
    }

    @Test
    void allocationAndAuditRollbackTogether() {
        runner().run(context -> {
            assertThatThrownBy(() -> tx(context, () -> {
                assertThat(allocate(context, 0, 1, "1")).isEqualTo(1);
                throw new IllegalStateException("rollback");
            })).hasMessage("rollback");
            assertThat(sql.queryForObject("SELECT allocated_qty FROM bty_source_allocation_guard", BigDecimal.class))
                    .isEqualByComparingTo("0");
            assertThat(sql.queryForObject("SELECT version FROM bty_source_allocation_guard", Long.class)).isZero();
            assertThat(sql.queryForObject("SELECT updated_by FROM bty_source_allocation_guard", String.class))
                    .isEqualTo("SEED");
        });
    }

    @Test
    void entityMapperInsertUsesPrimaryAuditAndSubjectDataScope() {
        runner().run(context -> {
            Object guard = row("BtySourceAllocationGuard", "entityNo", "E", "guardNo", "NEW", "sourceKind",
                    "STORE_ORDER", "sourceNo", "OTHER", "sourceLineRef", "L", "sourceRevision", 1L,
                    "sourceQty", BigDecimal.ONE, "allocatedQty", BigDecimal.ZERO, "uom", "COUNT",
                    "sourceHash", "a".repeat(64));
            assertThat(tx(context, () -> mapper(context, "SourceAllocationGuardMapper").insert(guard))).isOne();
            assertThat(sql.queryForObject("SELECT created_by FROM bty_source_allocation_guard WHERE guard_no='NEW'",
                    String.class)).isEqualTo("ACTOR");
            DataScopeContext.set(DataScopeSpec.of(ScopeDim.MERCHANT, Set.of("FOREIGN")));
            assertThat(mapper(context, "SourceAllocationGuardMapper").selectList(null)).isEmpty();
        });
    }
}

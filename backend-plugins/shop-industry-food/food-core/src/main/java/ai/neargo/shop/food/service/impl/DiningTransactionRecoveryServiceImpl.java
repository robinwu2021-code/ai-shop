package ai.neargo.shop.food.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.entity.FnbOrderBatch;
import ai.neargo.shop.food.entity.FnbReturn;
import ai.neargo.shop.food.entity.FnbSettlement;
import ai.neargo.shop.food.mapper.FoodMappers.OrderBatchMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ReturnMapper;
import ai.neargo.shop.food.mapper.FoodMappers.SettlementMapper;
import ai.neargo.shop.food.service.DiningCheckoutService;
import ai.neargo.shop.food.service.DiningOrderService;
import ai.neargo.shop.food.service.DiningReturnService;
import ai.neargo.shop.food.service.DiningTransactionRecoveryService;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;

/**
 * Fair source projection queues. A claim commits before owner access; poison records retain a
 * bounded backoff.
 *
 * <p>The lease lives only in {@code next_recovery_at} and is claimed/retired by compare-and-set on
 * that column. The business {@code version} that clients echo (pay/cancel, staff cancel) is never
 * touched by a claim or a finish; only the owner services move it, under their row lock and
 * optimistic version check, when recovery actually projects a state change. A due row that is
 * merely re-leased therefore never 409s an open page (C2).
 */
public class DiningTransactionRecoveryServiceImpl implements DiningTransactionRecoveryService {
    private static final List<String> ORDER_PENDING =
            List.of("PREPARED", "PLACING", "UNKNOWN", "CANCEL_PENDING");
    private static final List<String> CHECKOUT_PENDING = List.of("FROZEN", "COLLECTING", "SETTLED");
    private static final LocalDateTime FINISHED = LocalDateTime.of(9999, 12, 31, 23, 59, 59);
    private final OrderBatchMapper batches;
    private final SettlementMapper settlements;
    private final ReturnMapper returns;
    private final DiningOrderService orders;
    private final DiningCheckoutService checkout;
    private final DiningReturnService afterSales;
    private final Clock clock;
    private final TransactionTemplate write;

    public DiningTransactionRecoveryServiceImpl(
            OrderBatchMapper batches,
            SettlementMapper settlements,
            ReturnMapper returns,
            DiningOrderService orders,
            DiningCheckoutService checkout,
            DiningReturnService afterSales,
            Clock clock,
            PlatformTransactionManager manager) {
        this.batches = batches;
        this.settlements = settlements;
        this.returns = returns;
        this.orders = orders;
        this.checkout = checkout;
        this.afterSales = afterSales;
        this.clock = clock;
        write = new TransactionTemplate(manager);
    }

    private enum Kind {
        ORDER,
        CHECKOUT,
        RETURN
    }

    private record Candidate(
            Kind kind,
            long id,
            String entity,
            String store,
            String number,
            LocalDateTime due,
            LocalDateTime created) {
        LocalDateTime effectiveDue() {
            return due != null
                    ? due
                    : created != null ? created : LocalDateTime.of(1970, 1, 1, 0, 0);
        }
    }

    @Override
    public Report recoverDue(int limit) {
        if (limit < 1 || limit > 200) throw BizException.of(ErrorCode.BAD_REQUEST);
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Recovery dispatch cannot hold a FOOD transaction");
        }
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        List<Candidate> due = candidates(now);
        due.sort(
                Comparator.comparing(Candidate::effectiveDue)
                        .thenComparing(Candidate::id)
                        .thenComparing(row -> row.kind().name()));
        int claimed = 0, complete = 0, pending = 0, failed = 0;
        for (Candidate row : due) {
            if (claimed == limit) break;
            LocalDateTime lease = now.plusMinutes(2);
            if (!transaction(() -> claim(row, now, lease))) continue;
            claimed++;
            try {
                boolean synchronizedTerminal = recover(row);
                if (synchronizedTerminal) {
                    transaction(() -> finish(row, lease));
                    complete++;
                } else pending++;
            } catch (RuntimeException ownerUnavailableOrConflict) {
                // No second key/charge or cancellation inference; another due record can now
                // advance.
                failed++;
            }
        }
        // Complete counts source projections, never full-batch refund or kitchen completion.
        return new Report(claimed, complete, pending, failed);
    }

    private List<Candidate> candidates(LocalDateTime now) {
        return sql(
                () -> {
                    List<Candidate> result = new ArrayList<>();
                    batches.selectList(
                                    Wrappers.<FnbOrderBatch>lambdaQuery()
                                            .eq(FnbOrderBatch::getTenantNo, "MAIN")
                                            .isNotNull(FnbOrderBatch::getFrozenRequest)
                                            .in(FnbOrderBatch::getStatus, ORDER_PENDING)
                                            .ne(FnbOrderBatch::getPayMode, "CHANNEL")
                                            .and(
                                                    q ->
                                                            q.isNull(
                                                                            FnbOrderBatch
                                                                                    ::getNextRecoveryAt)
                                                                    .or()
                                                                    .le(
                                                                            FnbOrderBatch
                                                                                    ::getNextRecoveryAt,
                                                                            now))
                                            .last(
                                                    "ORDER BY"
                                                        + " COALESCE(next_recovery_at,created_at),id"
                                                        + " LIMIT 200"))
                            .forEach(
                                    row ->
                                            result.add(
                                                    new Candidate(
                                                            Kind.ORDER,
                                                            row.getId(),
                                                            row.getEntityNo(),
                                                            row.getStoreNo(),
                                                            row.getBatchNo(),
                                                            row.getNextRecoveryAt(),
                                                            row.getCreatedAt())));
                    settlements
                            .selectList(
                                    Wrappers.<FnbSettlement>lambdaQuery()
                                            .eq(FnbSettlement::getTenantNo, "MAIN")
                                            .isNotNull(FnbSettlement::getRequestSnapshot)
                                            .in(FnbSettlement::getStatus, CHECKOUT_PENDING)
                                            .and(
                                                    q ->
                                                            q.isNull(
                                                                            FnbSettlement
                                                                                    ::getNextRecoveryAt)
                                                                    .or()
                                                                    .le(
                                                                            FnbSettlement
                                                                                    ::getNextRecoveryAt,
                                                                            now))
                                            .last(
                                                    "ORDER BY"
                                                        + " COALESCE(next_recovery_at,created_at),id"
                                                        + " LIMIT 200"))
                            .forEach(
                                    row ->
                                            result.add(
                                                    new Candidate(
                                                            Kind.CHECKOUT,
                                                            row.getId(),
                                                            row.getEntityNo(),
                                                            row.getStoreNo(),
                                                            row.getSettlementNo(),
                                                            row.getNextRecoveryAt(),
                                                            row.getCreatedAt())));
                    returns.selectList(
                                    Wrappers.<FnbReturn>lambdaQuery()
                                            .eq(FnbReturn::getTenantNo, "MAIN")
                                            .isNotNull(FnbReturn::getRequestSnapshot)
                                            .notIn(FnbReturn::getStatus, "CLOSED", "REFUNDED")
                                            .and(
                                                    q ->
                                                            q.isNull(FnbReturn::getFinancialStatus)
                                                                    .or()
                                                                    .ne(
                                                                            FnbReturn
                                                                                    ::getFinancialStatus,
                                                                            "NOT_REQUESTED"))
                                            .and(
                                                    q ->
                                                            q.isNull(FnbReturn::getNextRecoveryAt)
                                                                    .or()
                                                                    .le(
                                                                            FnbReturn
                                                                                    ::getNextRecoveryAt,
                                                                            now))
                                            .last(
                                                    "ORDER BY"
                                                        + " COALESCE(next_recovery_at,created_at),id"
                                                        + " LIMIT 200"))
                            .forEach(
                                    row ->
                                            result.add(
                                                    new Candidate(
                                                            Kind.RETURN,
                                                            row.getId(),
                                                            row.getEntityNo(),
                                                            row.getStoreNo(),
                                                            row.getReturnNo(),
                                                            row.getNextRecoveryAt(),
                                                            row.getCreatedAt())));
                    return result;
                });
    }

    private boolean claim(Candidate row, LocalDateTime now, LocalDateTime lease) {
        // CAS on the lease value read by the scan; deliberately no version predicate or version
        // step.
        return switch (row.kind()) {
            case ORDER ->
                    batches.update(
                                    null,
                                    Wrappers.<FnbOrderBatch>lambdaUpdate()
                                            .eq(FnbOrderBatch::getTenantNo, "MAIN")
                                            .eq(FnbOrderBatch::getId, row.id())
                                            .eq(FnbOrderBatch::getEntityNo, row.entity())
                                            .eq(FnbOrderBatch::getStoreNo, row.store())
                                            .isNull(
                                                    row.due() == null,
                                                    FnbOrderBatch::getNextRecoveryAt)
                                            .eq(
                                                    row.due() != null,
                                                    FnbOrderBatch::getNextRecoveryAt,
                                                    row.due())
                                            .in(FnbOrderBatch::getStatus, ORDER_PENDING)
                                            .and(
                                                    q ->
                                                            q.isNull(
                                                                            FnbOrderBatch
                                                                                    ::getNextRecoveryAt)
                                                                    .or()
                                                                    .le(
                                                                            FnbOrderBatch
                                                                                    ::getNextRecoveryAt,
                                                                            now))
                                            .set(FnbOrderBatch::getNextRecoveryAt, lease))
                            == 1;
            case CHECKOUT ->
                    settlements.update(
                                    null,
                                    Wrappers.<FnbSettlement>lambdaUpdate()
                                            .eq(FnbSettlement::getTenantNo, "MAIN")
                                            .eq(FnbSettlement::getId, row.id())
                                            .eq(FnbSettlement::getEntityNo, row.entity())
                                            .eq(FnbSettlement::getStoreNo, row.store())
                                            .isNull(
                                                    row.due() == null,
                                                    FnbSettlement::getNextRecoveryAt)
                                            .eq(
                                                    row.due() != null,
                                                    FnbSettlement::getNextRecoveryAt,
                                                    row.due())
                                            .in(FnbSettlement::getStatus, CHECKOUT_PENDING)
                                            .and(
                                                    q ->
                                                            q.isNull(
                                                                            FnbSettlement
                                                                                    ::getNextRecoveryAt)
                                                                    .or()
                                                                    .le(
                                                                            FnbSettlement
                                                                                    ::getNextRecoveryAt,
                                                                            now))
                                            .set(FnbSettlement::getNextRecoveryAt, lease))
                            == 1;
            case RETURN ->
                    returns.update(
                                    null,
                                    Wrappers.<FnbReturn>lambdaUpdate()
                                            .eq(FnbReturn::getTenantNo, "MAIN")
                                            .eq(FnbReturn::getId, row.id())
                                            .eq(FnbReturn::getEntityNo, row.entity())
                                            .eq(FnbReturn::getStoreNo, row.store())
                                            .isNull(row.due() == null, FnbReturn::getNextRecoveryAt)
                                            .eq(
                                                    row.due() != null,
                                                    FnbReturn::getNextRecoveryAt,
                                                    row.due())
                                            .notIn(FnbReturn::getStatus, "CLOSED", "REFUNDED")
                                            .and(
                                                    q ->
                                                            q.isNull(FnbReturn::getFinancialStatus)
                                                                    .or()
                                                                    .ne(
                                                                            FnbReturn
                                                                                    ::getFinancialStatus,
                                                                            "NOT_REQUESTED"))
                                            .and(
                                                    q ->
                                                            q.isNull(FnbReturn::getNextRecoveryAt)
                                                                    .or()
                                                                    .le(
                                                                            FnbReturn
                                                                                    ::getNextRecoveryAt,
                                                                            now))
                                            .set(FnbReturn::getNextRecoveryAt, lease))
                            == 1;
        };
    }

    private boolean recover(Candidate row) {
        return switch (row.kind()) {
            case ORDER -> {
                var result = orders.recoverOriginal(row.entity(), row.store(), row.number());
                yield result != null
                        && List.of("BOUND", "ACCEPTED", "CANCELLED").contains(result.status());
            }
            case CHECKOUT -> {
                var result = checkout.recoverOriginal(row.entity(), row.store(), row.number());
                // Settled cash alone cannot accept original full kitchen quantities after a partial
                // refund.
                yield result != null
                        && "ACCEPTED".equals(result.batchStatus())
                        && "SETTLED".equals(result.status());
            }
            case RETURN -> {
                var result = afterSales.recoverOriginal(row.entity(), row.store(), row.number());
                yield result != null && List.of("CLOSED", "REFUNDED").contains(result.status());
            }
        };
    }

    private boolean finish(Candidate row, LocalDateTime lease) {
        // Version can advance during the original owner's local projection; the exact lease fences
        // a later claimant.
        // Retiring the lease is bookkeeping, so it leaves the business version alone.
        return switch (row.kind()) {
            case ORDER ->
                    batches.update(
                                    null,
                                    Wrappers.<FnbOrderBatch>lambdaUpdate()
                                            .eq(FnbOrderBatch::getId, row.id())
                                            .eq(FnbOrderBatch::getEntityNo, row.entity())
                                            .eq(FnbOrderBatch::getTenantNo, "MAIN")
                                            .eq(FnbOrderBatch::getStoreNo, row.store())
                                            .eq(FnbOrderBatch::getNextRecoveryAt, lease)
                                            .set(FnbOrderBatch::getNextRecoveryAt, FINISHED))
                            == 1;
            case CHECKOUT ->
                    settlements.update(
                                    null,
                                    Wrappers.<FnbSettlement>lambdaUpdate()
                                            .eq(FnbSettlement::getId, row.id())
                                            .eq(FnbSettlement::getEntityNo, row.entity())
                                            .eq(FnbSettlement::getTenantNo, "MAIN")
                                            .eq(FnbSettlement::getStoreNo, row.store())
                                            .eq(FnbSettlement::getNextRecoveryAt, lease)
                                            .set(FnbSettlement::getNextRecoveryAt, FINISHED))
                            == 1;
            case RETURN ->
                    returns.update(
                                    null,
                                    Wrappers.<FnbReturn>lambdaUpdate()
                                            .eq(FnbReturn::getId, row.id())
                                            .eq(FnbReturn::getEntityNo, row.entity())
                                            .eq(FnbReturn::getTenantNo, "MAIN")
                                            .eq(FnbReturn::getStoreNo, row.store())
                                            .eq(FnbReturn::getNextRecoveryAt, lease)
                                            .set(FnbReturn::getNextRecoveryAt, FINISHED))
                            == 1;
        };
    }

    private <T> T transaction(Supplier<T> action) {
        return sql(() -> write.execute(tx -> action.get()));
    }

    private static <T> T sql(Supplier<T> action) {
        return DataScopeContext.executeWithoutScope(action);
    }
}

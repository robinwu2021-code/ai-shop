package ai.neargo.shop.trade.order.points.impl;

import ai.neargo.shop.spi.points.PointsPaymentSnapshotPort;
import ai.neargo.shop.trade.order.mapper.OrderPointsFactMapper;
import ai.neargo.shop.trade.order.mapper.OrderPointsPlacementMapper;
import ai.neargo.shop.trade.order.mapper.OrderPointsRefundBindingMapper;
import ai.neargo.shop.trade.order.points.*;
import ai.neargo.shop.trade.service.AfterSaleService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Each item uses its original identity; a failed item is reported and cannot hide the next item.
 */
@Service
public class OrderPointsRecoveryServiceImpl implements OrderPointsRecoveryService {
    private final PointsOrderPlacementCoordinator placements;
    private final OrderPointsRefundCoordinator refunds;
    private final OrderPointsFactService facts;
    private final OrderPointsOutboxConsumer delivery;
    private final OrderPointsClosureFlowService closure;
    private final OrderPointsBindingService bindings;
    private final OrderPointsClosureScan closureScan;
    private final OrderPointsPlacementMapper placementRows;
    private final OrderPointsFactMapper factRows;
    private final OrderPointsRefundBindingMapper refundRows;
    private final AfterSaleService afterSales;
    private final PointsPaymentSnapshotPort payments;
    private final AtomicInteger nextQueue = new AtomicInteger();

    public OrderPointsRecoveryServiceImpl(
            PointsOrderPlacementCoordinator placements,
            OrderPointsRefundCoordinator refunds,
            OrderPointsFactService facts,
            OrderPointsOutboxConsumer delivery,
            OrderPointsClosureFlowService closure,
            OrderPointsBindingService bindings,
            OrderPointsClosureScan closureScan,
            OrderPointsPlacementMapper placementRows,
            OrderPointsFactMapper factRows,
            OrderPointsRefundBindingMapper refundRows,
            AfterSaleService afterSales,
            PointsPaymentSnapshotPort payments) {
        this.placements = placements;
        this.refunds = refunds;
        this.facts = facts;
        this.delivery = delivery;
        this.closure = closure;
        this.bindings = bindings;
        this.closureScan = closureScan;
        this.placementRows = placementRows;
        this.factRows = factRows;
        this.refundRows = refundRows;
        this.afterSales = afterSales;
        this.payments = payments;
    }

    @Override
    public Report recover(int limit) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("POINTS_RECOVERY_REQUIRES_NO_TRANSACTION");
        if (limit < 1 || limit > 200)
            throw new IllegalArgumentException("POINTS_RECOVERY_LIMIT_INVALID");
        int quota = Math.max(1, limit / 4);
        List<Failure> failures = new ArrayList<>();
        int attempted = 0, completed = 0;
        int start = nextQueue.getAndUpdate(current -> (current + 1) % 4);
        for (int offset = 0; offset < 4 && attempted < limit; offset++) {
            int queue = (start + offset) % 4;
            for (var item : pending(queue, Math.min(quota, limit - attempted))) {
                attempted++;
                try {
                    item.action().run();
                    completed++;
                } catch (RuntimeException e) {
                    failures.add(failure(item.reference(), e));
                }
            }
        }
        return new Report(attempted, completed, failures);
    }

    private List<RecoveryItem> pending(int queue, int limit) {
        List<RecoveryItem> result = new ArrayList<>();
        switch (queue) {
            case 0 -> {
                for (var no : placements.pending(limit))
                    result.add(new RecoveryItem("ORDER:" + no, () -> placements.resume(no)));
            }
            case 1 -> {
                for (var no : refunds.pending(limit))
                    result.add(
                            new RecoveryItem(
                                    "AFTER_SALE:" + no,
                                    () -> afterSales.resumeRefund(no, "SYSTEM")));
            }
            case 2 -> {
                for (var event : facts.pending(limit))
                    result.add(
                            new RecoveryItem(
                                    "EVENT:" + event,
                                    () -> {
                                        var source =
                                                facts.find(event)
                                                        .orElseThrow(
                                                                () ->
                                                                        new IllegalArgumentException(
                                                                                "POINTS_SOURCE_EVENT_UNKNOWN"));
                                        delivery.deliver(event, source.subOrderNo());
                                    }));
            }
            case 3 -> {
                for (var sub : closureScan.next(limit))
                    result.add(
                            new RecoveryItem(
                                    "SUB:" + sub,
                                    () -> {
                                        closure.finalizeSubOrder(sub);
                                        closure.closeRefundResponsibility(sub);
                                    }));
            }
            default -> throw new IllegalArgumentException("POINTS_RECOVERY_QUEUE_INVALID");
        }
        return result;
    }

    private record RecoveryItem(String reference, Runnable action) {}

    @Override
    public Inspection inspect(String subOrderNo) {
        if (subOrderNo == null || subOrderNo.isBlank())
            throw new IllegalArgumentException("POINTS_RECOVERY_SUB_REQUIRED");
        var binding = bindings.find(subOrderNo);
        if (binding == null)
            return new Inspection(
                    subOrderNo,
                    null,
                    0,
                    null,
                    0,
                    null,
                    List.of(),
                    0,
                    false,
                    List.of("POINTS_NOT_PARTICIPATING"));
        var placement = placements.inspect(binding.admission().orderNo());
        long pending = factRows.pendingSub(subOrderNo);
        var refundPhases =
                refundRows.forSubOrder(subOrderNo).stream()
                        .map(row -> (String) row.get("phase"))
                        .toList();
        List<String> differences = new ArrayList<>();
        if (placement == null) differences.add("PLACEMENT_MISSING");
        if (pending > 0) differences.add("SOURCE_EVENT_PENDING");
        if (refundPhases.stream().anyMatch(x -> !"COMMITTED".equals(x)))
            differences.add("REFUND_PENDING");
        if (refundPhases.contains("COMPENSATING"))
            differences.add("REFUND_COMPENSATION_PENDING");
        if (refundPhases.contains("COMPENSATED_PENDING_OPERATOR")
                || refundPhases.contains("CASH_FAILED_PENDING_OPERATOR"))
            differences.add("REFUND_OPERATOR_ACTION_REQUIRED");
        boolean verified = false;
        try {
            verified = payments.verified(binding.admission().orderNo()).isPresent();
        } catch (RuntimeException e) {
            differences.add("PAYMENT_PROOF_UNAVAILABLE");
        }
        if (!verified
                && placement != null
                && placement.phase() == PointsOrderPlacementCoordinator.Phase.READY)
            differences.add("PAYMENT_UNVERIFIED");
        return new Inspection(
                subOrderNo,
                binding.phase().name(),
                binding.version(),
                placement == null ? null : placement.phase().name(),
                placement == null ? 0 : placement.version(),
                binding.holdNo(),
                refundPhases,
                pending,
                verified,
                differences);
    }

    @Override
    public Backlog backlog(Instant now) {
        Objects.requireNonNull(now,"now");
        return new Backlog(queue(placementRows.pendingSummary(),now),
                queue(refundRows.pendingSummary(),now),queue(factRows.pendingSummary(),now));
    }

    private static QueueBacklog queue(Map<String,Object> row, Instant now) {
        long count=((Number)row.get("total")).longValue();
        Object oldest=row.get("oldest");
        long age=oldest==null ? 0 : Math.max(0,
                java.time.Duration.between(((Timestamp)oldest).toInstant(),now).getSeconds());
        return new QueueBacklog(count,age);
    }

    private static Failure failure(String ref, RuntimeException cause) {
        String code = cause.getMessage();
        if (code == null || !code.matches("[A-Z0-9_]{3,100}"))
            code = cause.getClass().getSimpleName();
        return new Failure(ref, code);
    }
}

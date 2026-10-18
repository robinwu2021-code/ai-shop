package ai.neargo.shop.trade.order.points.impl;

import ai.neargo.shop.spi.points.PointsPaymentSnapshotPort;
import ai.neargo.shop.trade.order.mapper.OrderPointsBindingMapper;
import ai.neargo.shop.trade.order.mapper.OrderPointsFactMapper;
import ai.neargo.shop.trade.order.mapper.OrderPointsRefundBindingMapper;
import ai.neargo.shop.trade.order.points.*;
import ai.neargo.shop.trade.service.AfterSaleService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
    private final OrderPointsBindingMapper bindingRows;
    private final OrderPointsFactMapper factRows;
    private final OrderPointsRefundBindingMapper refundRows;
    private final AfterSaleService afterSales;
    private final PointsPaymentSnapshotPort payments;
    private final AtomicReference<String> cursor = new AtomicReference<>("");
    private final AtomicInteger nextQueue = new AtomicInteger();

    public OrderPointsRecoveryServiceImpl(
            PointsOrderPlacementCoordinator placements,
            OrderPointsRefundCoordinator refunds,
            OrderPointsFactService facts,
            OrderPointsOutboxConsumer delivery,
            OrderPointsClosureFlowService closure,
            OrderPointsBindingService bindings,
            OrderPointsBindingMapper bindingRows,
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
        this.bindingRows = bindingRows;
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
                String after = cursor.get();
                var subs = bindingRows.scanSubOrders(after, limit);
                if (subs.isEmpty() && !after.isEmpty()) {
                    cursor.set("");
                    subs = bindingRows.scanSubOrders("", limit);
                }
                for (var sub : subs)
                    result.add(
                            new RecoveryItem(
                                    "SUB:" + sub,
                                    () -> {
                                        cursor.set(sub);
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

    private static Failure failure(String ref, RuntimeException cause) {
        String code = cause.getMessage();
        if (code == null || !code.matches("[A-Z0-9_]{3,100}"))
            code = cause.getClass().getSimpleName();
        return new Failure(ref, code);
    }
}

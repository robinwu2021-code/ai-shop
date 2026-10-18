package ai.neargo.shop.pay.points.job.impl;

import ai.neargo.shop.pay.points.event.PointsEventQueue;
import ai.neargo.shop.pay.points.event.PointsEventQueue.Message;
import ai.neargo.shop.pay.points.job.PointsRecoveryService;
import ai.neargo.shop.pay.points.lifecycle.ExpiryExecutionService;
import ai.neargo.shop.pay.points.payout.PointsPayoutExecutionService;
import ai.neargo.shop.pay.points.policy.PointsDigests;
import ai.neargo.shop.pay.points.pool.PoolWithdrawalExecutionService;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsStatus;
import ai.neargo.shop.pay.points.refund.PointsReceivableRecoveryExecutionService;
import ai.neargo.shop.pay.points.refund.PointsRefundAbortService;
import ai.neargo.shop.pay.points.refund.PointsRefundCollectionExecutionService;
import ai.neargo.shop.pay.points.refund.PointsRefundCompensationExecutionService;
import ai.neargo.shop.pay.points.refund.impl.PointsRefundPreparationServiceImpl.RefundSnapshot;
import ai.neargo.shop.pay.points.store.PointsCursorStore;
import ai.neargo.shop.pay.points.store.PointsOperationsStore;
import ai.neargo.shop.pay.points.store.PointsOperationsStore.Kind;
import ai.neargo.shop.pay.points.store.PointsReadStore;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * Rotating category cursors prevent a busy payout backlog from starving refunds and income
 * transfers.
 */
@Service
public class PointsRecoveryServiceImpl implements PointsRecoveryService {
    private final PointsOperationsStore store;
    private final PointsReadStore read;
    private final PointsCursorStore cursors;
    private final PointsEventQueue queue;
    private final PointsPayoutExecutionService payouts;
    private final ExpiryExecutionService expiry;
    private final PoolWithdrawalExecutionService withdrawals;
    private final PointsReceivableRecoveryExecutionService recoveries;
    private final PointsRefundCollectionExecutionService collections;
    private final PointsRefundCompensationExecutionService compensations;
    private final PointsRefundAbortService aborts;
    private final ObjectMapper json = new ObjectMapper();

    public PointsRecoveryServiceImpl(
            PointsOperationsStore store,
            PointsReadStore read,
            PointsCursorStore cursors,
            PointsEventQueue queue,
            PointsPayoutExecutionService payouts,
            ExpiryExecutionService expiry,
            PoolWithdrawalExecutionService withdrawals,
            PointsReceivableRecoveryExecutionService recoveries,
            PointsRefundCollectionExecutionService collections,
            PointsRefundCompensationExecutionService compensations,
            PointsRefundAbortService aborts) {
        this.store = store;
        this.read = read;
        this.cursors = cursors;
        this.queue = queue;
        this.payouts = payouts;
        this.expiry = expiry;
        this.withdrawals = withdrawals;
        this.recoveries = recoveries;
        this.collections = collections;
        this.compensations = compensations;
        this.aborts = aborts;
    }

    @Override
    public void schedule(Work work) {
        require(
                work != null
                        && work.operation() != null
                        && work.positionNo() != null
                        && !work.positionNo().isBlank(),
                "POINTS_RECOVERY_WORK_INVALID");
        String body = json.writeValueAsString(work);
        queue.enqueue(
                work.typeCode(),
                "RECOVER:" + PointsDigests.sha256(body),
                new Message(PointsEventQueue.Kind.FUNDS_RECOVERY, body));
    }

    @Override
    public int discover(String type, int limit, long now) {
        require(
                !TransactionSynchronizationManager.isActualTransactionActive(),
                "POINTS_RECOVERY_REQUIRES_NO_TRANSACTION");
        require(limit > 0 && limit <= 200 && now >= 0, "POINTS_BATCH_INVALID");
        String task = "PTS_RECOVER:" + type;
        var state = cursors.state(task);
        var positions = List.of(Kind.PAYOUT, Kind.TRANSFER, Kind.REFUND);
        Kind kind = positions.get((int) (state.watermark() % positions.size()));
        var marks = (ObjectNode) json.readTree(state.payload());
        long after = marks.path(kind.name()).asLong(0);
        List<String> statuses =
                switch (kind) {
                    case PAYOUT -> List.of("READY", "DISPATCHING", "UNKNOWN");
                    case TRANSFER -> List.of("READY", "DISPATCHING", "UNKNOWN", "FAILED");
                    case REFUND ->
                            List.of(
                                    "COLLECTION_REQUIRED",
                                    "COLLECTING",
                                    "COLLECTION_UNKNOWN",
                                    "COMPENSATION_REQUIRED",
                                    "COMPENSATING",
                                    "COMPENSATION_UNKNOWN");
                    default -> throw new IllegalStateException("POINTS_RECOVERY_KIND_INVALID");
                };
        var page = store.pending(type, kind, statuses, after, limit);
        int scheduled = 0;
        for (var row : page) {
            var body = json.readTree((String) row.get("payload_json"));
            if (kind == Kind.TRANSFER
                    && "FAILED".equals(row.get("state"))
                    && !body.hasNonNull("expiryRevision")) {
                continue;
            }
            Operation operation =
                    kind == Kind.PAYOUT
                            ? Operation.PAYOUT
                            : kind == Kind.TRANSFER
                                    ? Operation.TRANSFER
                                    : row.get("state").toString().startsWith("COMPENSAT")
                                            ? Operation.REFUND_COMPENSATION
                                            : Operation.REFUND_COLLECTION;
            schedule(new Work(type, operation, (String) row.get("position_no")));
            scheduled++;
        }
        marks.put(
                kind.name(),
                page.size() < limit ? 0 : ((Number) page.getLast().get("id")).longValue());
        cursors.advance(
                task,
                type,
                state.watermark(),
                Math.addExact(state.watermark(), 1),
                json.writeValueAsString(marks));
        return scheduled;
    }

    @Override
    public FundsStatus execute(Work work) {
        require(
                !TransactionSynchronizationManager.isActualTransactionActive(),
                "POINTS_RECOVERY_REQUIRES_NO_TRANSACTION");
        Kind kind =
                switch (work.operation()) {
                    case PAYOUT -> Kind.PAYOUT;
                    case TRANSFER -> Kind.TRANSFER;
                    default -> Kind.REFUND;
                };
        var head = read.position(kind.table(), work.positionNo());
        require(
                head != null && work.typeCode().equals(head.get("type_code")),
                "POINTS_RECOVERY_WORK_MISMATCH");
        var snapshot = json.readTree((String) head.get("payload_json"));
        return switch (work.operation()) {
            case PAYOUT -> payouts.execute(work.positionNo()).status();
            case REFUND_COLLECTION -> collections.execute(work.positionNo()).status();
            case REFUND_COMPENSATION -> {
                var result = compensations.execute(work.positionNo());
                if (result.status() == FundsStatus.SUCCEEDED) {
                    var saved = json.treeToValue(snapshot, RefundSnapshot.class);
                    aborts.abort(saved.compensation().abort());
                }
                yield result.status();
            }
            case TRANSFER -> {
                if (snapshot.hasNonNull("expiryRevision")) {
                    yield expiry.execute(work.positionNo()).status();
                }
                if (snapshot.hasNonNull("receivableNo")) {
                    yield recoveries.execute(work.positionNo()).status();
                }
                if (snapshot.hasNonNull("request")) {
                    yield withdrawals.execute(work.positionNo()).status();
                }
                throw new IllegalArgumentException("POINTS_RECOVERY_TRANSFER_INVALID");
            }
        };
    }

    private void require(boolean condition, String reason) {
        if (!condition) {
            throw new IllegalArgumentException(reason);
        }
    }
}

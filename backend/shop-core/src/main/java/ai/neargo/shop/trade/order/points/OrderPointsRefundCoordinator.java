package ai.neargo.shop.trade.order.points;

import ai.neargo.shop.spi.points.PointsPaymentSnapshotPort.VerifiedPayment;
import ai.neargo.shop.spi.points.PointsRefundPort;
import ai.neargo.shop.trade.order.refund.model.RefundPlan;
import ai.neargo.shop.spi.settle.SettlePort;

import java.util.List;

/** Source-owned original refund decision; pay and bank calls happen after staging commits. */
public interface OrderPointsRefundCoordinator {
    Binding stage(String afterSaleNo, RefundPlan plan, VerifiedPayment payment);

    Binding acceptPreparation(String afterSaleNo, PointsRefundPort.RefundPreparation preparation);

    Binding authorizeCash(String afterSaleNo, PointsRefundPort.CashDecision decision);

    Binding recordCashSuccess(String afterSaleNo, SettlePort.RefundEvidence evidence);

    Binding recordCashFailure(String afterSaleNo, SettlePort.RefundEvidence evidence);

    Binding recordZeroCash(String afterSaleNo);

    Binding acceptPointsConfirmation(String afterSaleNo, PointsRefundPort.RefundOutcome outcome);

    Binding commit(String afterSaleNo);

    Binding inspect(String afterSaleNo);

    List<String> pending(int limit);

    enum Phase {
        STAGED,
        PREPARED,
        CASH_AUTHORIZED,
        CASH_OPENING,
        CASH_PENDING,
        CASH_CONFIRMED,
        ZERO_AUTHORIZED,
        POINTS_CONFIRMED,
        COMMITTED,
        COMPENSATING,
        ABORTED
    }

    record Binding(
            String afterSaleNo,
            String subOrderNo,
            Phase phase,
            long version,
            PointsRefundPort.RefundCommand command,
            PointsRefundPort.RefundPreparation preparation,
            String cashPaymentNo) {}
}

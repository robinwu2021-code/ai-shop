package ai.neargo.shop.trade.order.refund.policy;

import ai.neargo.shop.trade.order.machine.order.OrderStatus;
import ai.neargo.shop.trade.order.refund.model.RefundClosureFacts;
import ai.neargo.shop.trade.order.refund.model.RefundClosureDecision;
import ai.neargo.shop.trade.order.refund.model.RefundProgress;

/** Cash progress never proves that the goods transaction is closed. No IO. */
public final class RefundClosurePolicy {
    public RefundClosureDecision decide(RefundClosureFacts facts) {
        boolean closed = facts.deliveredQty() + facts.terminatedQty() == facts.orderedQty();
        boolean terminated = facts.terminatedQty() == facts.orderedQty();
        OrderStatus target = !closed || !facts.approvedObligationsSettled() ? null
                : terminated ? OrderStatus.REFUNDED : OrderStatus.COMPLETED;
        return new RefundClosureDecision(RefundProgress.of(facts.refundedCashMinor(), facts.originalCashMinor()),
                closed, terminated, facts.approvedObligationsSettled(), target);
    }
}

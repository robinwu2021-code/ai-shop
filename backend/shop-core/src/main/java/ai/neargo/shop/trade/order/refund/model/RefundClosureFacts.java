package ai.neargo.shop.trade.order.refund.model;

/** Disjoint goods quantities and actual cash facts, loaded by an authorized service. */
public record RefundClosureFacts(long orderedQty, long deliveredQty, long terminatedQty,
        long originalCashMinor, long refundedCashMinor, boolean approvedObligationsSettled) {
    public RefundClosureFacts {
        if (orderedQty <= 0 || deliveredQty < 0 || terminatedQty < 0
                || deliveredQty > orderedQty || terminatedQty > orderedQty - deliveredQty
                || originalCashMinor < 0 || refundedCashMinor < 0 || refundedCashMinor > originalCashMinor) {
            throw new IllegalArgumentException("REFUND_CLOSURE_FACTS_INVALID");
        }
    }
}

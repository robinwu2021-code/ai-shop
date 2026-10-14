package ai.neargo.shop.trade.order.refund.model;

/**
 * 订单行的可退情况（值对象）。
 *
 * @param paidShareMinor 本行分摊后实付（快照，或存量单按同一算法现算）
 */
public record RefundableLine(long itemId, String title, int orderedQty, int refundedQty,
                             long paidShareMinor, long refundedMinor) {

    public int remainingQty() {
        return Math.max(0, orderedQty - refundedQty);
    }

    public long remainingMinor() {
        return Math.max(0L, paidShareMinor - refundedMinor);
    }
}

package ai.neargo.shop.trade.order.refund.model;

/** 退款进度：与订单状态正交 —— 部分退款不改订单状态，只改它。 */
public enum RefundProgress {
    NONE, PARTIAL, FULL;

    /** 按已退额与子单实付判（0 元单无可退现金，视为 NONE） */
    public static RefundProgress of(long refundedMinor, long payAmountMinor) {
        if (refundedMinor <= 0) {
            return NONE;
        }
        return refundedMinor >= payAmountMinor ? FULL : PARTIAL;
    }
}

package ai.neargo.shop.trade.order.refund.policy;

/** 抵扣积分随退款退回多少。 */
public interface PointsRefundPolicy {

    /** 退回的抵扣积分折现额（分） */
    long pointsBackMinor(long pointsDeductMinor, long payAmountMinor, long refundMinor);
}

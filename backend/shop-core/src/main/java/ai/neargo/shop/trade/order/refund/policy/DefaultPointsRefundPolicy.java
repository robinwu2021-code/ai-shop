package ai.neargo.shop.trade.order.refund.policy;

/** 按退款额 / 子单实付 比例退回，四舍五入；部分退不退等于吞了买家的钱。 */
public class DefaultPointsRefundPolicy implements PointsRefundPolicy {

    @Override
    public long pointsBackMinor(long pointsDeductMinor, long payAmountMinor, long refundMinor) {
        if (pointsDeductMinor <= 0 || payAmountMinor <= 0 || refundMinor <= 0) {
            return 0L;
        }
        long back = Math.round((double) pointsDeductMinor * refundMinor / payAmountMinor);
        return Math.min(back, pointsDeductMinor);
    }
}

package ai.neargo.shop.trade.order.service.refund;

import java.util.List;

/**
 * 行级退款的纯算法（订单服务 2.0 · W6）。退款资金执行（回退分账/原路退款/积分/券）<b>复用现有引擎</b>，
 * 本类只提供新增的"行级粒度"计量：可退数量、按量金额、整单是否退完。
 */
public final class RefundCalc {

    private RefundCalc() {
    }

    /** 这一行还能退几件 = 已下单 − 已退（不为负）。 */
    public static int refundableQty(int orderedQty, int refundedQty) {
        return Math.max(0, orderedQty - refundedQty);
    }

    /** 本次退款额(分) = 单价 × 退款件数。 */
    public static long amount(long unitPrice, int refundQty) {
        return unitPrice * Math.max(0, refundQty);
    }

    /** 申请退 refundQty 件是否合法：正数且不超过可退。 */
    public static boolean canRefund(int orderedQty, int refundedQty, int refundQty) {
        return refundQty > 0 && refundQty <= refundableQty(orderedQty, refundedQty);
    }

    /** 整单是否已退完（所有行都退满）—— 退券/退积分的判据。 */
    public static boolean wholeOrderRefunded(List<LineRefund> lines) {
        return !lines.isEmpty() && lines.stream().allMatch(l -> l.refundedQty() >= l.orderedQty());
    }
}

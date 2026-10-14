package ai.neargo.shop.trade.order.refund.model;

/**
 * 一次退款执行的结果。
 *
 * @param paymentNo 支付侧退款流水号；null = 原收款流水找不到（钱照退，账待人工补）
 */
public record RefundOutcome(RefundPlan plan, LedgerResult ledger, String paymentNo) {
}

package ai.neargo.shop.trade.order.refund.model;

/** 账本记完一笔之后：子单退款进度与累计已退额。 */
public record LedgerResult(RefundProgress progress, long subRefundedMinor) {
}

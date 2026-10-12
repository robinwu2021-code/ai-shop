package ai.neargo.shop.trade.order.service.refund;

/** 一行的退款量：已下单数量 vs 已退数量。行级退款（买三退一）的计量单元。 */
public record LineRefund(int orderedQty, int refundedQty) {
}

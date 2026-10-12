package ai.neargo.shop.trade.order.service.refund;

/** 一行当前的退款信息：已下单数量、已退数量、单价(分)。 */
public record LineRefundInfo(int orderedQty, int refundedQty, long unitPrice) {
}

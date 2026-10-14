package ai.neargo.shop.trade.order.refund.model;

/** 这次退的一行：哪一行、几件、多少钱。 */
public record RefundLine(long itemId, int qty, long amountMinor) {
}

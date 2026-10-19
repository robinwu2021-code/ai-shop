package ai.neargo.shop.trade.order.contract;

/** Buyer list stays one row per store order, with amounts and revision from that child. */
public record StoreOrderSummary(String subOrderNo, String payOrderNo, String storeNo, String status,
                                long payAmountMinor, long refundedMinor, long version,
                                String fulfillment, String title, String cover, int itemCount) {
}

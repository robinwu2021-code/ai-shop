package ai.neargo.shop.trade.order.contract;

import java.util.List;

/** Child fulfillment view; payOrderNo is only the payment aggregation reference. No physical IDs are exposed. */
public record StoreOrderView(String subOrderNo, String payOrderNo, String storeNo, String status,
                             long payAmountMinor, long refundedMinor, long version,
                             List<Item> items, List<Shipment> shipments) {
    public record Item(String goodsNo, String skuNo, String title, String cover, String spec,
                       long priceMinor, int qty, long amountMinor, boolean gift, String shipmentNo) {
    }

    public record Shipment(String shipmentNo, String shipStoreNo, String carrier, String waybillNo, String status) {
    }
}

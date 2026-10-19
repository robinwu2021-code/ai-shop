package ai.neargo.shop.trade.order.service.query;

import ai.neargo.shop.trade.order.contract.StoreOrderSummary;
import ai.neargo.shop.trade.order.contract.StoreOrderView;
import ai.neargo.shop.trade.service.OrderStatusView;

/** Only saved order/item/shipment facts enter this projection; no repricing or external tracking. */
public final class StoreOrderAssembler {
    private StoreOrderAssembler() {
    }

    public static StoreOrderView view(StoreOrderMaterial material) {
        var order = material.order();
        return new StoreOrderView(order.getSubOrderNo(), order.getOrderNo(), order.getStoreNo(), status(material),
                nz(order.getPayAmount()), nz(order.getRefundedMinor()), nz(order.getVersion()),
                material.items().stream().map(line -> new StoreOrderView.Item(line.getGoodsNo(), line.getSkuNo(),
                        line.getTitle(), line.getCover(), line.getSpec(), nz(line.getPrice()),
                        line.getQty() == null ? 0 : line.getQty(), nz(line.getAmount()),
                        Boolean.TRUE.equals(line.getIsGift()), line.getShipmentNo())).toList(),
                material.shipments().stream().map(shipment -> new StoreOrderView.Shipment(shipment.getShipmentNo(),
                        shipment.getShipStoreNo(), shipment.getCarrier(), shipment.getWaybillNo(),
                        shipment.getStatus())).toList());
    }

    public static StoreOrderSummary summary(StoreOrderMaterial material) {
        var order = material.order();
        var first = material.items().isEmpty() ? null : material.items().get(0);
        return new StoreOrderSummary(order.getSubOrderNo(), order.getOrderNo(), order.getStoreNo(), status(material),
                nz(order.getPayAmount()), nz(order.getRefundedMinor()), nz(order.getVersion()), order.getFulfillment(),
                first == null ? null : first.getTitle(), first == null ? null : first.getCover(),
                material.items().stream().mapToInt(item -> item.getQty() == null ? 0 : item.getQty()).sum());
    }

    private static String status(StoreOrderMaterial material) {
        return OrderStatusView.toContract(material.order().getStatus(), material.payment().getStatus());
    }

    private static long nz(Long number) {
        return number == null ? 0L : number;
    }
}

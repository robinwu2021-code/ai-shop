package ai.neargo.shop.trade.order.service.ship;

import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.order.entity.OrdShipment;

/**
 * 建发货单（订单服务 2.0 · W4）。一次发货 = 一张 {@link OrdShipment}（CREATED），
 * 关联它发出的那些订单行（{@link #bind}）。支持一个订单多张发货单（部分发货/多包裹）。
 */
public final class ShipmentDraftFactory {

    private ShipmentDraftFactory() {
    }

    /** 快递发货单。 */
    public static OrdShipment express(String shipmentNo, String subOrderNo,
                                      String shipStoreNo, String carrier, String waybillNo) {
        return build(shipmentNo, subOrderNo, shipStoreNo, "EXPRESS", carrier, waybillNo);
    }

    public static OrdShipment build(String shipmentNo, String subOrderNo, String shipStoreNo,
                                    String fulfillment, String carrier, String waybillNo) {
        OrdShipment s = new OrdShipment();
        s.setShipmentNo(shipmentNo);
        s.setSubOrderNo(subOrderNo);
        s.setShipStoreNo(shipStoreNo);
        s.setFulfillment(fulfillment);
        s.setCarrier(carrier);
        s.setWaybillNo(waybillNo);
        s.setStatus(OrdShipment.CREATED);
        return s;
    }

    /** 把一行绑到这张发货单：记归属 + 整件发出（不支持行内分批，见领域假设⑥）。 */
    public static void bind(OrdItem item, String shipmentNo) {
        item.setShipmentNo(shipmentNo);
        item.setShippedQty(item.getQty() == null ? 0 : item.getQty());
    }
}

package ai.neargo.shop.trade.order.machine.shipment;

/** 发货单事件（完整方案 §4 H1–H6）。 */
public enum ShipmentEvent {
    CREATE, PICKED_UP, SIGNED, RECEIPT_CONFIRMED, CORRECT_WAYBILL, CANCEL
}

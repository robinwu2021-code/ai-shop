package ai.neargo.shop.trade.order.machine.shipment;

/** 发货单（ord_shipment）状态。名字与 OrdShipment 常量逐字一致。 */
public enum ShipmentStatus {
    CREATED, SHIPPED, DELIVERED, RECEIVED, VOIDED
}

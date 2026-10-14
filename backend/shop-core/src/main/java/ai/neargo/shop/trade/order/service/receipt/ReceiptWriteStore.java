package ai.neargo.shop.trade.order.service.receipt;

import ai.neargo.shop.trade.order.entity.OrdShipment;

/** 收货写出口（小 port）。 */
public interface ReceiptWriteStore {

    void saveShipment(OrdShipment shipment);
}

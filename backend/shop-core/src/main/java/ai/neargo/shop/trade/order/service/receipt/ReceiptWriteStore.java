package ai.neargo.shop.trade.order.service.receipt;

import ai.neargo.shop.trade.order.entity.OrdShipment;
import ai.neargo.shop.trade.order.transition.Operator;

/** 收货写出口（小 port）。 */
public interface ReceiptWriteStore {

    /** A dispatched CREATED package may be received without inventing a carrier pickup event. */
    void receiveShipment(OrdShipment shipment, Operator operator, boolean dispatched);
}

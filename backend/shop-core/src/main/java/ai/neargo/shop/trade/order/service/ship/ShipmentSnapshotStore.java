package ai.neargo.shop.trade.order.service.ship;

import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.order.entity.OrdShipment;
import ai.neargo.shop.trade.order.machine.shipment.ShipmentEvent;

/** Dedicated package fact adapter keeps source checks and canonical state writes under the child lock. */
public interface ShipmentSnapshotStore {
    Facts currentLocked(String shipmentNo);
    void advance(OrdShipment shipment, ShipmentEvent event);
    void signedAt(OrdShipment shipment, long at);

    record Facts(OrdSubOrder order, OrdShipment shipment) { }
}

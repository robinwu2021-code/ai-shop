package ai.neargo.shop.trade.order.service.ship.impl;

import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.order.entity.OrdShipment;
import ai.neargo.shop.trade.order.machine.shipment.ShipmentEvent;
import ai.neargo.shop.trade.order.service.ship.ShipmentSnapshotConverger;
import ai.neargo.shop.trade.order.service.ship.ShipmentSnapshotStore;
import ai.neargo.shop.trade.order.service.ship.WaybillNormalizer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Set;

/** Immutable dispatch generation and current normalized waybill are checked before the existing state gate. */
@Service
public class ShipmentSnapshotConvergerImpl implements ShipmentSnapshotConverger {
    private static final Set<String> MOVING = Set.of("PICKED_UP", "IN_TRANSIT", "DELIVERING", "DELIVERED");
    private final ShipmentSnapshotStore store;

    public ShipmentSnapshotConvergerImpl(ShipmentSnapshotStore store) { this.store = store; }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public void converge(String shipmentNo, long version, String waybillNo, String status, Long signedAt) {
        if (shipmentNo == null || version != 0 || !MOVING.contains(status == null ? "" : status)) {
            return;
        }
        ShipmentSnapshotStore.Facts facts = store.currentLocked(shipmentNo);
        if (facts == null || !OrdSubOrder.FULFILLING.equals(facts.order().getStatus())) {
            return;
        }
        OrdShipment shipment = facts.shipment();
        if (!Objects.equals(WaybillNormalizer.normalize(shipment.getWaybillNo()),
                WaybillNormalizer.normalize(waybillNo)) || OrdShipment.RECEIVED.equals(shipment.getStatus())) {
            return;
        }
        if ("DELIVERED".equals(status) && (signedAt == null || signedAt <= 0)) {
            return;
        }
        if (OrdShipment.CREATED.equals(shipment.getStatus())) {
            store.advance(shipment, ShipmentEvent.PICKED_UP);
        }
        if ("DELIVERED".equals(status)) {
            if (OrdShipment.SHIPPED.equals(shipment.getStatus())) {
                store.advance(shipment, ShipmentEvent.SIGNED);
            }
            if (OrdShipment.DELIVERED.equals(shipment.getStatus())) {
                store.signedAt(shipment, signedAt);
            }
        }
    }
}

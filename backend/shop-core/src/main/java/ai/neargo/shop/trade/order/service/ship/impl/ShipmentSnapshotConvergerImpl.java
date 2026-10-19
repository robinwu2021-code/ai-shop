package ai.neargo.shop.trade.order.service.ship.impl;

import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.order.entity.OrdShipment;
import ai.neargo.shop.trade.order.machine.shipment.ShipmentEvent;
import ai.neargo.shop.trade.order.service.ship.ShipmentSnapshotConverger;
import ai.neargo.shop.trade.order.service.ship.ShipmentSnapshotStore;
import ai.neargo.shop.trade.order.service.ship.WaybillNormalizer;
import ai.neargo.shop.trade.order.service.receipt.OrderReceiptCompleter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Set;
import java.time.ZoneId;

/** Immutable dispatch generation and current normalized waybill are checked before the existing state gate. */
@Service
public class ShipmentSnapshotConvergerImpl implements ShipmentSnapshotConverger {
    private static final Set<String> MOVING = Set.of("PICKED_UP", "IN_TRANSIT", "DELIVERING", "DELIVERED");
    private static final long DATETIME_PRECISION_MS = 1_000L;
    private final ShipmentSnapshotStore store;
    private final OrderReceiptCompleter receipts;

    public ShipmentSnapshotConvergerImpl(ShipmentSnapshotStore store, OrderReceiptCompleter receipts) {
        this.store = store;
        this.receipts = receipts;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public void converge(String shipmentNo, long version, String waybillNo, String status,
                         Long signedAt, Long signedNodeAt, Long firstTraceAt, String source) {
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
        if ("DELIVERED".equals(status) && (shipment.getCreatedAt() == null
                || signedAt < shipment.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                        - DATETIME_PRECISION_MS)) {
            // An old, already-finished tracking number is not proof that this order was received.
            return;
        }
        if (OrdShipment.CREATED.equals(shipment.getStatus())) {
            store.advance(shipment, ShipmentEvent.PICKED_UP, source);
        }
        if ("DELIVERED".equals(status)) {
            if (OrdShipment.SHIPPED.equals(shipment.getStatus())) {
                store.advance(shipment, ShipmentEvent.SIGNED, source);
            }
            if (OrdShipment.DELIVERED.equals(shipment.getStatus())) {
                long dispatchedAt = shipment.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
                boolean verified = signedNodeAt != null && signedNodeAt.equals(signedAt)
                        && firstTraceAt != null && firstTraceAt >= dispatchedAt - DATETIME_PRECISION_MS;
                if (verified) {
                    store.verifiedSignedAt(shipment, signedNodeAt);
                    receipts.completeSigned(facts.order().getSubOrderNo(), shipmentNo, signedNodeAt, source);
                } else {
                    store.signedAt(shipment, signedAt);
                }
            }
        }
    }
}

package ai.neargo.shop.trade.order.service.ship;

import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.order.entity.OrdShipment;
import ai.neargo.shop.trade.order.service.receipt.OrderReceiptCompleter;
import ai.neargo.shop.trade.order.service.ship.impl.ShipmentSnapshotConvergerImpl;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.mockito.Mockito.*;

class ShipmentSnapshotConvergerImplTest {
    private final ShipmentSnapshotStore store = mock(ShipmentSnapshotStore.class);
    private final OrderReceiptCompleter receipts = mock(OrderReceiptCompleter.class);
    private final ShipmentSnapshotConvergerImpl converger = new ShipmentSnapshotConvergerImpl(store, receipts);

    private long shipment() {
        OrdSubOrder order = new OrdSubOrder();
        order.setSubOrderNo("SUB1");
        order.setStatus(OrdSubOrder.FULFILLING);
        OrdShipment shipment = new OrdShipment();
        shipment.setShipmentNo("SRC1");
        shipment.setWaybillNo("WB1");
        shipment.setStatus(OrdShipment.DELIVERED);
        shipment.setCreatedAt(LocalDateTime.of(2026, 10, 15, 10, 0));
        shipment.setSignedAt(shipment.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() + 60_000);
        when(store.currentLocked("SRC1")).thenReturn(new ShipmentSnapshotStore.Facts(order, shipment));
        return shipment.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    @Test
    void laterDatedProofReplacesStatusOnlyTimestampAndCompletesOrder() {
        long dispatched = shipment();
        long signed = dispatched + 120_000;
        converger.converge("SRC1", 0, "WB1", "DELIVERED", signed, signed, dispatched + 30_000,
                "kuaidi100");
        verify(store).verifiedSignedAt(any(OrdShipment.class), eq(signed));
        verify(receipts).completeSigned("SUB1", "SRC1", signed, "kuaidi100");
    }

    @Test
    void reusedWaybillHistoryCannotCompleteOrder() {
        long dispatched = shipment();
        long signed = dispatched + 120_000;
        converger.converge("SRC1", 0, "WB1", "DELIVERED", signed, signed, dispatched - 86_400_000);
        verify(store, never()).verifiedSignedAt(any(), anyLong());
        verify(receipts, never()).completeSigned(any(), any(), anyLong(), any());
    }
}

package ai.neargo.shop.invbridge;

import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.event.SysOutboxMapper;
import ai.neargo.shop.event.outbox.runtime.UnifiedOutboxFacade;
import ai.neargo.inventory.domain.service.InventoryOrderTraceService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class InventoryTraceSyncProjectionTest {

    @Test
    void traceIncludesSourceOutboxStateForOrderFulfillmentAndReturnEvents() {
        InventoryOrderTraceService inventory = orderNo -> new InventoryOrderTraceService.InventoryOrderTrace(orderNo,
                List.of(new InventoryOrderTraceService.Reservation("PAY-1", "OWNER-1", "ISSUED", 2,
                        "OUT-1", LocalDateTime.parse("2026-10-19T10:00:00"), List.of(
                        new InventoryOrderTraceService.Line(1, "SUB-1", "ITEM-1", "LOC-1", 2, 2, 0,
                                "OUT-1", LocalDateTime.parse("2026-10-19T10:01:00"), "merchant-1", List.of(),
                                List.of(new InventoryOrderTraceService.ReturnReceipt("RR-1", "AS-1", 1,
                                        "SALEABLE", "LOC-1", "IN-1", "merchant-1",
                                        LocalDateTime.parse("2026-10-19T11:00:00"))))))));
        UnifiedOutboxFacade unified = mock(UnifiedOutboxFacade.class);
        SysOutboxMapper legacy = mock(SysOutboxMapper.class);
        when(unified.enabled("PLATFORM")).thenReturn(true);
        when(unified.statusOf("PLATFORM", "INVENTORY", "PAY-1", InvMirrorEvent.RESERVE)).thenReturn("SENT");
        when(unified.statusOf("PLATFORM", "INVENTORY", "PAY-1", InvMirrorEvent.COMMIT)).thenReturn("WARNING");
        when(unified.statusOf("PLATFORM", "INVENTORY", "PAY-1", InvMirrorEvent.ALLOCATE)).thenReturn("PENDING");
        when(unified.statusOf("PLATFORM", "INVENTORY", "SUB-1", InvMirrorEvent.ISSUE)).thenReturn("FAILED");
        when(unified.statusOf("PLATFORM", "INVENTORY", "RR-1", InvMirrorEvent.RETURN_RECEIPT)).thenReturn("SENT");

        var projection = new InventoryTraceSyncProjection(inventory, legacy);
        projection.setUnifiedOutbox(unified);
        var result = projection.trace("PAY-1");

        assertThat(result.reservations()).hasSize(1);
        assertThat(result.syncEvents()).extracting(InventoryOrderTraceService.SyncEvent::aggregateId)
                .containsExactly("PAY-1", "PAY-1", "PAY-1", "SUB-1", "RR-1");
        assertThat(result.syncEvents()).extracting(InventoryOrderTraceService.SyncEvent::status)
                .containsExactly("COMPLETED", "WARNING", "PENDING", "FAILED", "COMPLETED");
        assertThat(result.syncEvents()).allMatch(e -> e.source().equals("PLATFORM") && e.outboxMode().equals("UNIFIED"));
        verify(legacy, times(2)).selectOne(any());
    }

    @Test
    void traceFallsBackToLegacyOutboxWhenUnifiedSourceIsUnavailable() {
        UnifiedOutboxFacade unified = mock(UnifiedOutboxFacade.class);
        SysOutboxMapper legacy = mock(SysOutboxMapper.class);
        when(unified.enabled("PLATFORM")).thenReturn(false);
        SysOutbox event = new SysOutbox();
        event.setStatus(SysOutbox.FAILED);
        java.util.concurrent.atomic.AtomicInteger lookups = new java.util.concurrent.atomic.AtomicInteger();
        when(legacy.selectOne(any())).thenAnswer(invocation -> lookups.incrementAndGet() == 4 ? event : null);
        var inventory = (InventoryOrderTraceService) orderNo -> new InventoryOrderTraceService.InventoryOrderTrace(orderNo, List.of());

        var projection = new InventoryTraceSyncProjection(inventory, legacy);
        projection.setUnifiedOutbox(unified);
        var result = projection.trace("PAY-2");

        assertThat(result.syncEvents()).extracting(InventoryOrderTraceService.SyncEvent::status).containsExactly("FAILED");
        assertThat(result.syncEvents()).extracting(InventoryOrderTraceService.SyncEvent::outboxMode).containsExactly("LEGACY");
    }
}

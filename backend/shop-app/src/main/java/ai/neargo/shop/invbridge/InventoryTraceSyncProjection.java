package ai.neargo.shop.invbridge;

import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.event.SysOutboxMapper;
import ai.neargo.shop.event.outbox.runtime.UnifiedOutboxFacade;
import ai.neargo.shop.inventory.config.ConditionalOnInventory;
import ai.neargo.shop.inventory.service.InventoryOrderTraceService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Adds source-side outbox acknowledgement states to the read-only inventory ledger trace. */
@Service
@ConditionalOnInventory
public class InventoryTraceSyncProjection {
    private static final String AGGREGATE = "INVENTORY";
    private static final List<String> ORDER_EVENTS = List.of(
            InvMirrorEvent.RESERVE, InvMirrorEvent.COMMIT, InvMirrorEvent.ALLOCATE, InvMirrorEvent.RELEASE);

    private final InventoryOrderTraceService inventory;
    private final SysOutboxMapper legacyOutbox;
    private UnifiedOutboxFacade unifiedOutbox;

    public InventoryTraceSyncProjection(InventoryOrderTraceService inventory, SysOutboxMapper legacyOutbox) {
        this.inventory = inventory;
        this.legacyOutbox = legacyOutbox;
    }

    @Autowired(required = false)
    public void setUnifiedOutbox(UnifiedOutboxFacade unifiedOutbox) {
        this.unifiedOutbox = unifiedOutbox;
    }

    public InventoryOrderTraceService.InventoryOrderTrace trace(String orderNo) {
        var trace = inventory.trace(orderNo);
        Map<String, EventKey> candidates = new LinkedHashMap<>();
        ORDER_EVENTS.forEach(type -> add(candidates, orderNo, type));
        for (var reservation : trace.reservations()) {
            ORDER_EVENTS.forEach(type -> add(candidates, reservation.reservationId(), type));
            for (var line : reservation.lines()) {
                add(candidates, line.fulfillmentRef(), InvMirrorEvent.ISSUE);
                add(candidates, line.fulfillmentRef(), InvMirrorEvent.RELEASE_ALLOCATED);
                for (var receipt : line.returns()) {
                    add(candidates, receipt.receiptNo(), InvMirrorEvent.RETURN_RECEIPT);
                }
            }
        }
        List<InventoryOrderTraceService.SyncEvent> events = new ArrayList<>();
        for (EventKey key : candidates.values()) {
            var event = status(key);
            if (event != null) events.add(event);
        }
        return new InventoryOrderTraceService.InventoryOrderTrace(orderNo, trace.reservations(), List.copyOf(events));
    }

    private InventoryOrderTraceService.SyncEvent status(EventKey key) {
        String state = unifiedOutbox != null && unifiedOutbox.enabled("PLATFORM")
                ? unifiedOutbox.statusOf("PLATFORM", AGGREGATE, key.aggregateId(), key.eventType()) : null;
        String mode = "UNIFIED";
        if (state == null) {
            SysOutbox legacy = legacyOutbox.selectOne(Wrappers.<SysOutbox>lambdaQuery()
                    .eq(SysOutbox::getAggregateType, AGGREGATE)
                    .eq(SysOutbox::getAggregateId, key.aggregateId())
                    .eq(SysOutbox::getEventType, key.eventType())
                    .orderByDesc(SysOutbox::getId).last("LIMIT 1"));
            if (legacy == null) return null;
            state = legacy.getStatus();
            mode = "LEGACY";
        }
        return new InventoryOrderTraceService.SyncEvent(key.aggregateId(), key.eventType(), "PLATFORM",
                mapStatus(state), mode);
    }

    private static String mapStatus(String status) {
        return switch (status) {
            case SysOutbox.SENT -> "COMPLETED";
            case SysOutbox.FAILED -> "FAILED";
            case "WARNING" -> "WARNING";
            case "SKIPPED" -> "SKIPPED";
            case "ARCHIVED" -> "ARCHIVED";
            case "PROCESSING" -> "PENDING";
            default -> "PENDING";
        };
    }

    private static void add(Map<String, EventKey> candidates, String aggregateId, String type) {
        if (aggregateId == null || aggregateId.isBlank()) return;
        EventKey event = new EventKey(aggregateId, type);
        candidates.putIfAbsent(type + ":" + aggregateId, event);
    }

    private record EventKey(String aggregateId, String eventType) { }
}

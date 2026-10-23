package ai.neargo.shop.print.api;

import ai.neargo.shop.event.DomainEvent;

import java.time.Instant;

public record PrintBatchResolved(
        PrintScope scope,
        String batchNo,
        String resolvedStatus,
        Instant resolvedAt,
        String batchKind,
        FrozenJson notificationPolicy)
        implements DomainEvent {
    public String aggregateType() {
        return "PRINT";
    }

    public String aggregateId() {
        return batchNo;
    }

    public String eventType() {
        return "PRINT_BATCH_RESOLVED";
    }
}

package ai.neargo.shop.spi.printing;

import ai.neargo.shop.event.DomainEvent;

/** Shared outbox event enables authenticated delivery diagnostics without exposing document content. */
public record PrintJobChanged(String entityNo, String storeNo, String jobNo, String status,
        String deviceNo, int attemptCount) implements DomainEvent {
    @Override public String aggregateType() { return "PRINT_JOB"; }
    @Override public String aggregateId() { return jobNo; }
    @Override public String eventType() { return "PRINT_JOB_CHANGED"; }
}

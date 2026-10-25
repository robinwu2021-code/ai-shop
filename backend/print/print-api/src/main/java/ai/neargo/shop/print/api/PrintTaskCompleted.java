package ai.neargo.shop.print.api;

import ai.neargo.shop.event.DomainEvent;

import java.time.Instant;

public record PrintTaskCompleted(
        PrintScope scope,
        String jobNo,
        String rootJobNo,
        PrintJobKind jobKind,
        PrintEvidenceLevel evidenceLevel,
        Instant confirmedAt)
        implements DomainEvent {
    public String aggregateType() {
        return "PRINT";
    }

    public String aggregateId() {
        return jobNo;
    }

    public String eventType() {
        return "PRINT_TASK_COMPLETED";
    }
}

package ai.neargo.shop.print.api;

import ai.neargo.shop.event.DomainEvent;

public record PrintExportRequested(PrintScope scope, String exportNo) implements DomainEvent {
    public String aggregateType() {
        return "PRINT";
    }

    public String aggregateId() {
        return exportNo;
    }

    public String eventType() {
        return "PRINT_EXPORT_REQUESTED";
    }
}

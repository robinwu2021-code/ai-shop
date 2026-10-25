package ai.neargo.shop.print.api;

import ai.neargo.shop.event.DomainEvent;

public record PrintIncidentResolved(
        PrintScope scope, String incidentNo, FrozenJson notificationPolicy) implements DomainEvent {
    public String aggregateType() {
        return "PRINT";
    }

    public String aggregateId() {
        return incidentNo;
    }

    public String eventType() {
        return "PRINT_INCIDENT_RESOLVED";
    }
}

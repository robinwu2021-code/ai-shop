package ai.neargo.shop.print.api;

import ai.neargo.shop.event.DomainEvent;

public record PrintIncidentOpened(
        PrintScope scope,
        String incidentNo,
        String incidentType,
        String targetNo,
        FrozenJson notificationPolicy)
        implements DomainEvent {
    public String aggregateType() {
        return "PRINT";
    }

    public String aggregateId() {
        return incidentNo;
    }

    public String eventType() {
        return "PRINT_INCIDENT_OPENED";
    }
}

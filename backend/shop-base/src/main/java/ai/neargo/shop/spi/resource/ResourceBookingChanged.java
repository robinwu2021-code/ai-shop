package ai.neargo.shop.spi.resource;

import ai.neargo.shop.event.DomainEvent;

/** Safe committed booking evidence; excludes source payload, qualifications and authorization credentials. */
public record ResourceBookingChanged(String aggregateId, String entityNo, String storeNo, String commandNo,
        String holdNo, String action, long ownerRevision) implements DomainEvent {
    @Override
    public String aggregateType() { return "RESOURCE_BOOKING"; }
    @Override
    public String eventType() { return "RESOURCE_BOOKING_CHANGED"; }
}

package ai.neargo.shop.spi.food;

import ai.neargo.shop.event.DomainEvent;

/** A credential-free audit fact; no bearer, token hash or buyer data in event payloads. */
public record DiningSessionChanged(String entityNo, String storeNo, String tableNo, String sessionNo,
        String action, long version, String operationNo) implements DomainEvent {
    @Override public String aggregateType() { return "FOOD_GUEST_SESSION"; }
    @Override public String aggregateId() { return sessionNo; }
    @Override public String eventType() { return "FOOD_DINING_SESSION_CHANGED"; }
}

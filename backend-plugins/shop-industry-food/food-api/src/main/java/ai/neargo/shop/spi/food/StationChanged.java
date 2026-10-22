package ai.neargo.shop.spi.food;

import ai.neargo.shop.event.DomainEvent;

/** Atomic configuration invalidation does not command existing orders or kitchen work. */
public record StationChanged(String entityNo, String storeNo, String configNo, String action,
        long version, String operationNo, Object after) implements DomainEvent {
    @Override public String aggregateType() { return "FOOD_STATION"; }
    @Override public String aggregateId() { return storeNo + ":" + configNo; }
    @Override public String eventType() { return "FOOD_STATION_CHANGED"; }
}

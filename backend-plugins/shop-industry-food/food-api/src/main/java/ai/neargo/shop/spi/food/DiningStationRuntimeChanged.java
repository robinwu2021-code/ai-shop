package ai.neargo.shop.spi.food;

import ai.neargo.shop.event.DomainEvent;

import java.time.Instant;

/** Voice status is an actual bridge receipt; QUEUED/SENT never mean played. */
public record DiningStationRuntimeChanged(
        String entityNo,
        String storeNo,
        String stationNo,
        String commandNo,
        String action,
        long sourceVersion,
        String status,
        String reason,
        Instant occurredAt)
        implements DomainEvent {
    @Override
    public String aggregateType() {
        return "FOOD_STATION_RUNTIME";
    }

    @Override
    public String aggregateId() {
        return stationNo;
    }

    @Override
    public String eventType() {
        return "DINING_STATION_RUNTIME_CHANGED";
    }
}

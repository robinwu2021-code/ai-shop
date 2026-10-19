package ai.neargo.shop.spi.points;

import ai.neargo.shop.event.DomainEvent;

/** Durable wake-up reference; the source placement row owns the original command. */
public record OrderPointsPlacementWakeEvent(String orderNo) implements DomainEvent {
    public static final String TYPE = "ORDER_POINTS_PLACEMENT_WAKE";

    @Override
    public String aggregateType() {
        return "ORDER";
    }

    @Override
    public String aggregateId() {
        return orderNo;
    }

    @Override
    public String eventType() {
        return TYPE;
    }
}

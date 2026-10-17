package ai.neargo.shop.spi.points;

import ai.neargo.shop.event.DomainEvent;

/** Transport reference; immutable business body lives in the source journal. */
public record OrderPointsSourceEvent(String eventNo, String subOrderNo) implements DomainEvent {
    public static final String TYPE = "ORDER_POINTS_SOURCE_FACT";

    @Override
    public String aggregateType() {
        return "SUB_ORDER";
    }

    @Override
    public String aggregateId() {
        return subOrderNo;
    }

    @Override
    public String eventType() {
        return TYPE;
    }
}

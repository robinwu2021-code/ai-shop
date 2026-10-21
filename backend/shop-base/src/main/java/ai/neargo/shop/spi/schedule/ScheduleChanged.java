package ai.neargo.shop.spi.schedule;

import ai.neargo.shop.event.DomainEvent;

/** Immutable schedule evidence excludes free-text reasons and downstream task claims. */
public record ScheduleChanged(String aggregateId, String entityNo, String storeNo, String ruleNo,
        long revision, String requestRef) implements DomainEvent {
    @Override
    public String aggregateType() { return "MERCHANT_RESOURCE"; }
    @Override
    public String eventType() { return "MERCHANT_SCHEDULE_CHANGED"; }
}

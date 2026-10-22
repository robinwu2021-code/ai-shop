package ai.neargo.shop.spi.food;

import ai.neargo.shop.event.DomainEvent;
import java.time.Instant;

/** Policy publication fact; consumers must not infer transaction permission from this event. */
public record StorePolicyPublished(String entityNo, String storeNo, String policyNo, long version,
        Instant effectiveFrom, String operationNo) implements DomainEvent {
    @Override public String aggregateType() { return "FOOD_STORE_POLICY"; }
    @Override public String aggregateId() { return storeNo; }
    @Override public String eventType() { return "FOOD_STORE_POLICY_PUBLISHED"; }
}

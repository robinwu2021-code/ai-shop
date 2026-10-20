package ai.neargo.shop.promotion.dto;

import ai.neargo.shop.event.DomainEvent;

/** The source decision is durable before any payment command is sent; payload contains no permit or raw code. */
public record ActivityBenefitDecided(String tenantNo, String intentNo, String userNo, long occurredAt)
        implements DomainEvent {
    @Override public String aggregateType() { return "ACTIVITY_BENEFIT_INTENT"; }
    @Override public String aggregateId() { return intentNo; }
    @Override public String eventType() { return "ACTIVITY_BENEFIT_DECIDED"; }
}

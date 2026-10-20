package ai.neargo.shop.promotion.dto;

import ai.neargo.shop.event.DomainEvent;

/** Durable recovery trigger. No code, member reference, permit or collection evidence in the event. */
public record StoredValueRechargePrepared(String tenantNo, String intentNo, String entityNo, String rechargeNo,
                                          long occurredAt) implements DomainEvent {
    @Override public String aggregateType() { return "RECHARGE_INTENT"; }
    @Override public String aggregateId() { return intentNo; }
    @Override public String eventType() { return "STORED_VALUE_RECHARGE_PREPARED"; }
}

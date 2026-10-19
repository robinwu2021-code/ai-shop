package ai.neargo.shop.spi.entitlement;

import ai.neargo.shop.event.DomainEvent;

public final class EntitlementOrderEvents {
    private EntitlementOrderEvents() { }
    public record UseDecided(String tenantNo, String entityNo, String operationNo) implements DomainEvent {
        @Override public String aggregateType() { return "ENTITLEMENT_ORDER_USE"; }
        @Override public String aggregateId() { return operationNo; }
        @Override public String eventType() { return "ENTITLEMENT_ORDER_USE_DECIDED"; }
    }
}

package ai.neargo.shop.print.api;

import ai.neargo.shop.event.DomainEvent;

public record OrderPrintControlChanged(PrintControlFact fact) implements DomainEvent {
    public String aggregateType() {
        return "ORDER";
    }

    public String aggregateId() {
        return fact.orderNo();
    }

    public String eventType() {
        return "ORDER_PRINT_CONTROL_CHANGED";
    }
}

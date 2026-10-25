package ai.neargo.shop.print.api;

import ai.neargo.shop.event.DomainEvent;

public record OrderPrintFactReady(String orderNo, String subOrderNo, PrintFactRef factRef)
        implements DomainEvent {
    public String aggregateType() {
        return "ORDER";
    }

    public String aggregateId() {
        return orderNo;
    }

    public String eventType() {
        return "ORDER_PRINT_FACT_READY";
    }
}

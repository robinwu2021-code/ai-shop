package ai.neargo.shop.print.api;
import ai.neargo.shop.event.DomainEvent;
/** Generic wakeup for an already committed immutable receipt fact. */
public record BusinessPrintFactReady(String sourceNo, PrintFactRef factRef) implements DomainEvent {
    public String aggregateType() { return "BUSINESS_PRINT_FACT"; }
    public String aggregateId() { return sourceNo; }
    public String eventType() { return "BUSINESS_PRINT_FACT_READY"; }
}

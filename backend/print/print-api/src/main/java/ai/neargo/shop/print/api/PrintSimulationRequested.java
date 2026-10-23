package ai.neargo.shop.print.api;

import ai.neargo.shop.event.DomainEvent;

public record PrintSimulationRequested(PrintScope scope, String simulationNo)
        implements DomainEvent {
    public String aggregateType() {
        return "PRINT";
    }

    public String aggregateId() {
        return simulationNo;
    }

    public String eventType() {
        return "PRINT_SIMULATION_REQUESTED";
    }
}

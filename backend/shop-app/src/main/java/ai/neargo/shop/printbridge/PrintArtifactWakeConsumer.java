package ai.neargo.shop.printbridge;

import ai.neargo.shop.event.*;
import ai.neargo.shop.print.api.*;
import ai.neargo.shop.print.export.PrintExportWorker;
import ai.neargo.shop.print.simulation.SimulationRunner;

import org.springframework.stereotype.Component;

import java.time.Duration;

@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "shop.print.enabled",
        havingValue = "true")
@Component
public class PrintArtifactWakeConsumer implements OutboxConsumer {
    private final SimulationRunner simulations;
    private final PrintExportWorker exports;

    public PrintArtifactWakeConsumer(SimulationRunner s, PrintExportWorker e) {
        simulations = s;
        exports = e;
    }

    public String handlerId() {
        return "print-artifact-wake";
    }

    public boolean externalWorkflow() {
        return true;
    }

    public boolean supports(String type) {
        return java.util.Set.of("PRINT_EXPORT_REQUESTED", "PRINT_SIMULATION_REQUESTED")
                .contains(type);
    }

    public void consume(SysOutbox row) {
        var budget = new PrintWorkerBudget(10, Duration.ofSeconds(20), "print-outbox");
        var r =
                row.getEventType().equals("PRINT_EXPORT_REQUESTED")
                        ? exports.generateNext(budget)
                        : simulations.runNext(budget);
        if (r.failed() > 0) throw new IllegalStateException("Print artifact processing failed");
    }
}

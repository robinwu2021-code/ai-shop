package ai.neargo.shop.printing.center.simulation;

import ai.neargo.shop.print.api.*;
import ai.neargo.shop.printing.center.dispatch.PrintStateModels.*;

import java.util.*;

public final class SimulationModels {
    private SimulationModels() {}

    public record SimulationDispatch(
            String documentKey, SimulationFixtureCode code, int queryIndex) {}

    public record SimulationStep(
            long virtualMillis, String eventNo, PrintObservation observation) {}

    public record SimulationFixture(
            SimulationFixtureCode code,
            String version,
            List<SimulationStep> steps,
            PrintJobStatus expectedStatus) {
        public SimulationFixture {
            steps = List.copyOf(steps);
        }
    }

    public record SimulationInput(List<PrintLayout> layouts, Set<String> requiredDataScopes) {
        public SimulationInput {
            layouts = List.copyOf(layouts);
            requiredDataScopes = Set.copyOf(requiredDataScopes);
        }
    }
}

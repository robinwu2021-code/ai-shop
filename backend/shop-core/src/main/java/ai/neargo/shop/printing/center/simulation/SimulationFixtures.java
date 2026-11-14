package ai.neargo.shop.printing.center.simulation;

import static ai.neargo.shop.printing.center.simulation.SimulationModels.*;

import ai.neargo.shop.print.api.*;
import ai.neargo.shop.printing.center.dispatch.PrintStateModels.*;

import org.springframework.stereotype.Component;

import java.util.*;

@Component
public final class SimulationFixtures {
    public SimulationFixture require(SimulationFixtureCode code, String version) {
        if (!"1".equals(version)) throw new PrintFailure("print.fixtureVersionUnavailable");
        var accepted =
                new SimulationStep(
                        0,
                        "e1",
                        new PrintObservation(
                                ObservedPrintStatus.ACCEPTED,
                                PrintEvidenceLevel.NONE,
                                false,
                                false));
        var confirmed =
                new SimulationStep(
                        100,
                        "e2",
                        new PrintObservation(
                                ObservedPrintStatus.CONFIRMED,
                                PrintEvidenceLevel.DEVICE_CONFIRMED,
                                true,
                                false));
        var unknown =
                new SimulationStep(
                        120000,
                        "e3",
                        new PrintObservation(
                                ObservedPrintStatus.UNKNOWN,
                                PrintEvidenceLevel.NONE,
                                false,
                                false));
        var rejected =
                new SimulationStep(
                        0,
                        "e1",
                        new PrintObservation(
                                ObservedPrintStatus.NOT_ACCEPTED,
                                PrintEvidenceLevel.NONE,
                                true,
                                false));
        return switch (code) {
            case SUCCESS ->
                    new SimulationFixture(
                            code, version, List.of(accepted, confirmed), PrintJobStatus.COMPLETED);
            case NOT_ACCEPTED, OFFLINE ->
                    new SimulationFixture(
                            code, version, List.of(rejected), PrintJobStatus.RETRY_WAIT);
            case PAPER_OUT ->
                    new SimulationFixture(
                            code,
                            version,
                            List.of(
                                    accepted,
                                    new SimulationStep(
                                            20,
                                            "e2",
                                            new PrintObservation(
                                                    ObservedPrintStatus.UNKNOWN,
                                                    PrintEvidenceLevel.NONE,
                                                    false,
                                                    true))),
                            PrintJobStatus.UNKNOWN);
            case ACCEPTED_NO_RESULT, TIMEOUT_UNKNOWN ->
                    new SimulationFixture(
                            code, version, List.of(accepted, unknown), PrintJobStatus.UNKNOWN);
            case DUPLICATE_RECEIPT ->
                    new SimulationFixture(
                            code,
                            version,
                            List.of(accepted, confirmed, confirmed),
                            PrintJobStatus.COMPLETED);
            case LATE_RECEIPT ->
                    new SimulationFixture(
                            code,
                            version,
                            List.of(
                                    accepted,
                                    unknown,
                                    new SimulationStep(130000, "e4", confirmed.observation())),
                            PrintJobStatus.COMPLETED);
            case PARTIAL_COPIES ->
                    new SimulationFixture(
                            code,
                            version,
                            List.of(
                                    new SimulationStep(
                                            20,
                                            "e1",
                                            new PrintObservation(
                                                    ObservedPrintStatus.CONFIRMED,
                                                    PrintEvidenceLevel.DEVICE_CONFIRMED,
                                                    false,
                                                    true))),
                            PrintJobStatus.UNKNOWN);
        };
    }
}

package ai.neargo.shop.printing.center.simulation;

import ai.neargo.shop.printing.center.simulation.SimulationModels.*;

import org.springframework.stereotype.Component;

@Component
public final class FixtureSimulatedDispatch implements SimulatedDispatch {
    private final SimulationFixtures fixtures;

    public FixtureSimulatedDispatch(SimulationFixtures fixtures) {
        this.fixtures = fixtures;
    }

    public SimulationStep submit(SimulationDispatch dispatch) {
        return fixtures.require(dispatch.code(), "1").steps().getFirst();
    }

    public SimulationStep query(SimulationDispatch dispatch) {
        var steps = fixtures.require(dispatch.code(), "1").steps();
        if (dispatch.queryIndex() < 0 || dispatch.queryIndex() >= steps.size())
            throw new IllegalArgumentException("fixture index");
        return steps.get(dispatch.queryIndex());
    }
}

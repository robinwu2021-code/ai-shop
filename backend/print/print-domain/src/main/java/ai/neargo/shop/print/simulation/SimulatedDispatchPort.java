package ai.neargo.shop.print.simulation;

import ai.neargo.shop.print.simulation.SimulationModels.*;

/** A simulation-only port; it cannot submit to production devices. */
public interface SimulatedDispatchPort {
    SimulationStep submit(SimulationDispatch dispatch);

    SimulationStep query(SimulationDispatch dispatch);
}

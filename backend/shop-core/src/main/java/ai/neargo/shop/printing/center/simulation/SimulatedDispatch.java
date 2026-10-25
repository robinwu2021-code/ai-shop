package ai.neargo.shop.printing.center.simulation;

import ai.neargo.shop.printing.center.simulation.SimulationModels.*;

/** A simulation-only port; it cannot submit to production devices. */
public interface SimulatedDispatch {
    SimulationStep submit(SimulationDispatch dispatch);

    SimulationStep query(SimulationDispatch dispatch);
}

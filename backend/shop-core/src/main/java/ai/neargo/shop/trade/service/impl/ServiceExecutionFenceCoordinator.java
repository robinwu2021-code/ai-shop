package ai.neargo.shop.trade.service.impl;

import ai.neargo.shop.trade.service.ServiceExecutionService;
import ai.neargo.shop.trade.service.ServiceExecutionService.ExecutionCoordinates;
import org.springframework.stereotype.Component;

/** Joins real admission changes to quantity suspension without a second transaction or remote owner call. */
@Component("coreServiceExecutionFenceCoordinator")
public final class ServiceExecutionFenceCoordinator {
    private final ServiceExecutionService service;

    /** This dependency has no admission service reference, so owner wiring remains acyclic. */
    public ServiceExecutionFenceCoordinator(ServiceExecutionService service) {
        this.service = service;
    }

    /** Caller already holds parent/children; acquire every source/unit/attempt before operation/declaration. */
    public void lockBeforeAdmissionLocked(ExecutionCoordinates coordinates) {
        service.lockSourceLocked(coordinates);
    }

    /** Caller retains those roots and records the corresponding admission action in the same transaction. */
    public int beforeAdmissionChangeLocked(ExecutionCoordinates coordinates, String reason) {
        return service.requestSuspendLocked(coordinates, reason);
    }
}

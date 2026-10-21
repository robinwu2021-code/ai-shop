package ai.neargo.shop.beauty.service.impl;

import ai.neargo.shop.beauty.dto.OperationVO;
import ai.neargo.shop.beauty.policy.AppointmentAccess;
import ai.neargo.shop.beauty.service.ServiceOperationRecoveryService;

/** Continues only the durable original operation; no source key, actor namespace or prepared bytes are replaced. */
public final class ServiceOperationRecoveryServiceImpl implements ServiceOperationRecoveryService {
    private final AppointmentPersistence db;
    private final AppointmentAccess access;
    private final AppointmentCoordinator coordinator;
    public ServiceOperationRecoveryServiceImpl(AppointmentPersistence db, AppointmentAccess access,
            AppointmentCoordinator coordinator) { this.db = db; this.access = access; this.coordinator = coordinator; }
    @Override
    public OperationVO recoverOperation(String entity, String store, String actor, String operationNo) {
        access.merchant(entity, store, actor, true);
        var operation = db.operation(operationNo);
        AppointmentPersistence.require(entity.equals(operation.getEntityNo()) && store.equals(operation.getStoreNo()));
        var result = coordinator.advance(operationNo);
        return new OperationVO(result.getOperationNo(), result.getAppointmentNo(), result.getState(),
                result.getAction(),
                "PENDING".equals(result.getState()) ? "AUTHENTICATED_OPERATION_RECOVERY"
                        : "RELEASE_REQUIRED".equals(result.getState()) ? "AUTHENTICATED_CANCEL" : null);
    }
}

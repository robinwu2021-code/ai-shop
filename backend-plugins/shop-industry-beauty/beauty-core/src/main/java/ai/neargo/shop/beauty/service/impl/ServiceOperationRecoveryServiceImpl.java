package ai.neargo.shop.beauty.service.impl;

import ai.neargo.shop.beauty.dto.OperationVO;
import ai.neargo.shop.beauty.entity.BtyReceptionOperation;
import ai.neargo.shop.beauty.mapper.BeautyMappers;
import ai.neargo.shop.beauty.policy.AppointmentAccess;
import ai.neargo.shop.beauty.policy.ReceptionAccess;
import ai.neargo.shop.beauty.service.ServiceOperationRecoveryService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

/** Continues only the durable original operation; no source key, actor namespace or prepared bytes are replaced. */
public final class ServiceOperationRecoveryServiceImpl implements ServiceOperationRecoveryService {
    private final AppointmentPersistence db;
    private final AppointmentAccess access;
    private final AppointmentCoordinator coordinator;
    private final BeautyMappers.ReceptionOperationMapper receptions;
    private final ReceptionAccess receptionAccess;
    private final ReceptionCoordinator reception;
    private final WorkOrderCoordinator orders;
    private final GuestRegistrationServiceImpl guests;
    public ServiceOperationRecoveryServiceImpl(AppointmentPersistence db, AppointmentAccess access,
            AppointmentCoordinator coordinator, BeautyMappers.ReceptionOperationMapper receptions,
            ReceptionAccess receptionAccess, ReceptionCoordinator reception, WorkOrderCoordinator orders,
            GuestRegistrationServiceImpl guests) {
        this.db = db; this.access = access; this.coordinator = coordinator; this.receptions = receptions;
        this.receptionAccess = receptionAccess; this.reception = reception; this.orders = orders; this.guests = guests;
    }
    @Override
    public OperationVO recoverOperation(String entity, String store, String actor, String operationNo) {
        access.merchant(entity, store, actor, true);
        var outer = db.read(() -> receptions.selectScoped(entity, store, operationNo));
        if (outer != null) {
            receptionAccess.customer(entity, store, actor);
            var current = ServiceOperationQueryServiceImpl.receptionView(outer);
            if (!"PENDING".equals(current.state())) { return current; }
            switch (outer.getAction()) {
                case "RECEIVE_WALK_IN" -> reception.recoverWalkIn(entity, store, actor, operationNo);
                case "OPEN_WORK_ORDER" -> orders.recover(entity, store, actor, operationNo);
                case "CLAIM_GUEST" -> {
                    var result = guests.recoverClaim(entity, store, actor, operationNo);
                    var persisted = db.read(() -> receptions.selectScoped(entity, store, operationNo));
                    return new OperationVO(operationNo, persisted.getAppointmentNo(), result.operationState(),
                            persisted.getAction(), result.recoveryAction(), persisted.getWorkOrderNo(),
                            persisted.getFailureCode());
                }
                default -> { return current; }
            }
            return ServiceOperationQueryServiceImpl.receptionView(
                    db.read(() -> receptions.selectScoped(entity, store, operationNo)));
        }
        var linkedOuter = db.read(() -> receptions.selectOne(Wrappers.<BtyReceptionOperation>lambdaQuery()
                .eq(BtyReceptionOperation::getTenantNo, "MAIN").eq(BtyReceptionOperation::getEntityNo, entity)
                .eq(BtyReceptionOperation::getStoreNo, store)
                .eq(BtyReceptionOperation::getAppointmentOperationNo, operationNo)
                .eq(BtyReceptionOperation::getAction, "RECEIVE_WALK_IN")
                .eq(BtyReceptionOperation::getEndpoint, "BAPI-048")));
        if (linkedOuter != null) {
            return recoverOperation(entity, store, actor, linkedOuter.getOperationNo());
        }
        var operation = db.operation(operationNo);
        AppointmentPersistence.require(entity.equals(operation.getEntityNo()) && store.equals(operation.getStoreNo()));
        var result = coordinator.advance(operationNo);
        return new OperationVO(result.getOperationNo(), result.getAppointmentNo(), result.getState(),
                result.getAction(),
                "PENDING".equals(result.getState()) ? "AUTHENTICATED_OPERATION_RECOVERY"
                        : "RELEASE_REQUIRED".equals(result.getState()) ? "AUTHENTICATED_CANCEL" : null);
    }
}

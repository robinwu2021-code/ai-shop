package ai.neargo.shop.beauty.service.impl;

import ai.neargo.shop.beauty.dto.OperationVO;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.beauty.entity.BtyReceptionOperation;
import ai.neargo.shop.beauty.mapper.BeautyMappers;
import ai.neargo.shop.beauty.policy.AppointmentAccess;
import ai.neargo.shop.beauty.policy.ReceptionAccess;
import ai.neargo.shop.beauty.service.ServiceOperationRecoveryService;
import ai.neargo.shop.beauty.service.ServiceSubmissionService;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import java.util.List;
import java.util.Objects;

/** Continues only the durable original operation; no source key, actor namespace or prepared bytes are replaced. */
public final class ServiceOperationRecoveryServiceImpl implements ServiceOperationRecoveryService {
    private final AppointmentPersistence db;
    private ServiceTaskStockPreparationCoordinator taskPreparation;
    private final AppointmentAccess access;
    private final AppointmentCoordinator coordinator;
    private final BeautyMappers.ReceptionOperationMapper receptions;
    private final ReceptionAccess receptionAccess;
    private final ReceptionCoordinator reception;
    private final WorkOrderCoordinator orders;
    private final GuestRegistrationServiceImpl guests;
    private final BeautyMappers.ServiceSubmissionMapper submissions;
    private final ServiceSubmissionService sources;

    public ServiceOperationRecoveryServiceImpl(
            AppointmentPersistence db,
            AppointmentAccess access,
            AppointmentCoordinator coordinator,
            BeautyMappers.ReceptionOperationMapper receptions,
            ReceptionAccess receptionAccess,
            ReceptionCoordinator reception,
            WorkOrderCoordinator orders,
            GuestRegistrationServiceImpl guests,
            BeautyMappers.ServiceSubmissionMapper submissions,
            ServiceSubmissionService sources) {
        this.db = db; this.access = access; this.coordinator = coordinator; this.receptions = receptions;
        this.receptionAccess = receptionAccess; this.reception = reception; this.orders = orders; this.guests = guests;
        this.submissions = submissions;
        this.sources = sources;
    }

    /** Real START original handling is configured alongside existing operation families. */
    public void setTaskPreparation(ServiceTaskStockPreparationCoordinator taskPreparation) {
        this.taskPreparation = Objects.requireNonNull(taskPreparation);
    }

    @Override
    public OperationVO recoverOperation(String entity, String store, String actor, String operationNo) {
        var source = db.read(() -> submissions.selectSubmission(entity, store, operationNo));
        if (source != null && "START".equals(source.getAction())) {
            if (taskPreparation == null)
                throw BizException.of(ErrorCode.BEAUTY_DEPENDENCY_UNAVAILABLE);
            var progress =
                    taskPreparation.resumeAcceptedOriginal(entity, store, actor, operationNo);
            return new OperationVO(
                    operationNo,
                    null,
                    progress.preparationState(),
                    "START",
                    progress.recoveryAction(),
                    progress.workOrderNo(),
                    progress.pendingReason(),
                    progress);
        }
        access.merchant(entity, store, actor, true);
        if (source != null && List.of("SUBMIT_SOURCE", "ADD_SOURCE").contains(source.getAction())) {
            sources.recoverPending(entity, store, actor, operationNo);
            return ServiceOperationQueryServiceImpl.sourceView(
                    db.read(() -> submissions.selectSubmission(entity, store, operationNo)));
        }
        var outer = db.read(() -> receptions.selectScoped(entity, store, operationNo));
        if (outer != null) {
            receptionAccess.customer(entity, store, actor);
            var current = ServiceOperationQueryServiceImpl.receptionView(outer);
            if (!"PENDING".equals(current.state())) { return current; }
            switch (outer.getAction()) {
                case "RECEIVE_WALK_IN" ->
                        reception.recoverWalkIn(entity, store, actor, operationNo);
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
        var linkedOuter =
                db.read(
                        () ->
                                receptions.selectOne(
                                        Wrappers.<BtyReceptionOperation>lambdaQuery()
                                                .eq(BtyReceptionOperation::getTenantNo, "MAIN")
                                                .eq(BtyReceptionOperation::getEntityNo, entity)
                                                .eq(BtyReceptionOperation::getStoreNo, store)
                                                .eq(
                                                        BtyReceptionOperation
                                                                ::getAppointmentOperationNo,
                                                        operationNo)
                                                .eq(
                                                        BtyReceptionOperation::getAction,
                                                        "RECEIVE_WALK_IN")
                                                .eq(
                                                        BtyReceptionOperation::getEndpoint,
                                                        "BAPI-048")));
        if (linkedOuter != null) {
            return recoverOperation(entity, store, actor, linkedOuter.getOperationNo());
        }
        var operation = db.operation(operationNo);
        AppointmentPersistence.require(entity.equals(operation.getEntityNo()) && store.equals(operation.getStoreNo()));
        var result = coordinator.advance(operationNo);
        return new OperationVO(
                result.getOperationNo(),
                result.getAppointmentNo(),
                result.getState(),
                result.getAction(),
                "PENDING".equals(result.getState())
                        ? "AUTHENTICATED_OPERATION_RECOVERY"
                        : "RELEASE_REQUIRED".equals(result.getState())
                                ? "AUTHENTICATED_CANCEL"
                                : null);
    }
}

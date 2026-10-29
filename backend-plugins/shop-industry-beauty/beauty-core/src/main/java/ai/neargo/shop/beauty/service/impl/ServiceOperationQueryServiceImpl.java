package ai.neargo.shop.beauty.service.impl;

import ai.neargo.shop.beauty.dto.OperationVO;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.beauty.entity.BtyReceptionOperation;
import ai.neargo.shop.beauty.entity.BtyServiceSubmission;
import ai.neargo.shop.beauty.mapper.BeautyMappers;
import ai.neargo.shop.beauty.policy.AppointmentAccess;
import ai.neargo.shop.beauty.policy.ServiceSubmissionAccess;
import ai.neargo.shop.beauty.service.ServiceOperationQueryService;

import java.util.List;
import java.util.Objects;

/** Pure original-number progress read. GET never creates or executes a new command. */
public final class ServiceOperationQueryServiceImpl implements ServiceOperationQueryService {
    private final AppointmentPersistence db;
    private ServiceTaskStockPreparationCoordinator taskPreparation;
    private final AppointmentAccess access;
    private final BeautyMappers.ReceptionOperationMapper receptions;
    private final BeautyMappers.ServiceSubmissionMapper submissions;
    private final ServiceSubmissionQuery sourceQuery;
    private final ServiceSubmissionAccess sourceAccess;

    public ServiceOperationQueryServiceImpl(
            AppointmentPersistence db,
            AppointmentAccess access,
            BeautyMappers.ReceptionOperationMapper receptions,
            BeautyMappers.ServiceSubmissionMapper submissions,
            ServiceSubmissionQuery sourceQuery,
            ServiceSubmissionAccess sourceAccess) {
        this.db = db;
        this.access = access;
        this.receptions = receptions;
        this.submissions = submissions;
        this.sourceQuery = sourceQuery;
        this.sourceAccess = sourceAccess;
    }

    /** Real START original handling is configured alongside existing operation families. */
    public void setTaskPreparation(ServiceTaskStockPreparationCoordinator taskPreparation) {
        this.taskPreparation = Objects.requireNonNull(taskPreparation);
    }

    @Override
    public OperationVO getOperation(String entity, String store, String actor, String number) {
        var source = db.read(() -> submissions.selectSubmission(entity, store, number));
        if (source != null && "START".equals(source.getAction())) {
            if (taskPreparation == null)
                throw BizException.of(ErrorCode.BEAUTY_DEPENDENCY_UNAVAILABLE);
            var progress = taskPreparation.projectOperation(entity, store, actor, number);
            return new OperationVO(
                    number,
                    null,
                    progress.preparationState(),
                    "START",
                    progress.recoveryAction(),
                    progress.workOrderNo(),
                    progress.pendingReason(),
                    progress);
        }
        access.merchant(entity, store, actor, false);
        if (source != null && List.of("SUBMIT_SOURCE", "ADD_SOURCE").contains(source.getAction())) {
            sourceAccess.authorize(entity, store, actor, false, false);
            sourceAccess.buyer(
                    sourceQuery.root(entity, store, source.getWorkOrderNo()).context(), null);
            sourceQuery.submission(entity, store, number);
            return sourceView(source);
        }
        var reception = db.read(() -> receptions.selectScoped(entity, store, number));
        if (reception != null) { return receptionView(reception); }
        var op = db.operation(number);
        AppointmentPersistence.require(entity.equals(op.getEntityNo()) && store.equals(op.getStoreNo()));
        return new OperationVO(number, op.getAppointmentNo(), op.getState(), op.getAction(),
                "PENDING".equals(op.getState()) ? "AUTHENTICATED_SAME_KEY_REPLAY"
                        : "RELEASE_REQUIRED".equals(op.getState()) ? "AUTHENTICATED_CANCEL" : null);
    }

    static OperationVO sourceView(BtyServiceSubmission row) {
        return new OperationVO(
                row.getSubmissionNo(),
                null,
                "BOUND".equals(row.getStatus())
                        ? "SUCCEEDED"
                        : "REJECTED".equals(row.getStatus()) ? "REJECTED" : "PENDING",
                row.getAction(),
                List.of("BOUND", "REJECTED").contains(row.getStatus())
                        ? null
                        : "AUTHENTICATED_ORIGINAL_RECOVERY",
                row.getWorkOrderNo(),
                row.getFailureCode());
    }

    static OperationVO receptionView(BtyReceptionOperation op) {
        boolean endpoint =
                switch (op.getAction()) {
                    case "RECEIVE_WALK_IN" -> "BAPI-048".equals(op.getEndpoint());
                    case "OPEN_WORK_ORDER" ->
                            "BAPI-052".equals(op.getEndpoint())
                                    || "BAPI-054".equals(op.getEndpoint());
                    case "CLAIM_GUEST" -> "BAPI-032".equals(op.getEndpoint());
                    case "CHECK_IN" ->
                            "BAPI-047".equals(op.getEndpoint())
                                    || "CAPI-154".equals(op.getEndpoint());
                    case "RECEPTION_EXCEPTION" -> "BAPI-053".equals(op.getEndpoint());
                    default -> false;
                };
        AppointmentPersistence.require(endpoint);
        String state = "SUCCEEDED".equals(op.getPhase()) || "REJECTED".equals(op.getPhase())
                ? op.getPhase() : "PENDING";
        String recovery = !"PENDING".equals(state) ? null
                : "RESOURCE_RELEASE_REQUIRED".equals(op.getFailureCode()) ? "AUTHENTICATED_CANCEL"
                : "ARRIVAL_REQUIRES_RESCHEDULE".equals(op.getFailureCode()) ? "REQUEST_RESCHEDULE"
                : "CLAIM_GUEST".equals(op.getAction()) ? "AUTHENTICATED_CLAIM_RECOVERY"
                : "CHECK_IN".equals(op.getAction()) || "RECEPTION_EXCEPTION".equals(op.getAction())
                        ? "REQUIRES_FRESH_IDENTITY_PROOF" : "AUTHENTICATED_OPERATION_RECOVERY";
        return new OperationVO(op.getOperationNo(), op.getAppointmentNo(), state, op.getAction(), recovery,
                op.getWorkOrderNo(), op.getFailureCode());
    }
}

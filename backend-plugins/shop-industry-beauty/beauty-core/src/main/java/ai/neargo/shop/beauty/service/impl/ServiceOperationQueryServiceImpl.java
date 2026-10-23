package ai.neargo.shop.beauty.service.impl;

import ai.neargo.shop.beauty.dto.OperationVO;
import ai.neargo.shop.beauty.entity.BtyReceptionOperation;
import ai.neargo.shop.beauty.mapper.BeautyMappers;
import ai.neargo.shop.beauty.policy.AppointmentAccess;
import ai.neargo.shop.beauty.service.ServiceOperationQueryService;

/** Pure original-number progress read. GET never creates or executes a new command. */
public final class ServiceOperationQueryServiceImpl implements ServiceOperationQueryService {
    private final AppointmentPersistence db;
    private final AppointmentAccess access;
    private final BeautyMappers.ReceptionOperationMapper receptions;
    public ServiceOperationQueryServiceImpl(AppointmentPersistence db, AppointmentAccess access,
            BeautyMappers.ReceptionOperationMapper receptions) {
        this.db = db; this.access = access; this.receptions = receptions;
    }
    @Override
    public OperationVO getOperation(String entity, String store, String actor, String number) {
        access.merchant(entity, store, actor, false);
        var reception = db.read(() -> receptions.selectScoped(entity, store, number));
        if (reception != null) { return receptionView(reception); }
        var op = db.operation(number);
        AppointmentPersistence.require(entity.equals(op.getEntityNo()) && store.equals(op.getStoreNo()));
        return new OperationVO(number, op.getAppointmentNo(), op.getState(), op.getAction(),
                "PENDING".equals(op.getState()) ? "AUTHENTICATED_SAME_KEY_REPLAY"
                        : "RELEASE_REQUIRED".equals(op.getState()) ? "AUTHENTICATED_CANCEL" : null);
    }
    static OperationVO receptionView(BtyReceptionOperation op) {
        boolean endpoint = switch (op.getAction()) {
            case "RECEIVE_WALK_IN" -> "BAPI-048".equals(op.getEndpoint());
            case "OPEN_WORK_ORDER" -> "BAPI-052".equals(op.getEndpoint()) || "BAPI-054".equals(op.getEndpoint());
            case "CLAIM_GUEST" -> "BAPI-032".equals(op.getEndpoint());
            case "CHECK_IN" -> "BAPI-047".equals(op.getEndpoint()) || "CAPI-154".equals(op.getEndpoint());
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

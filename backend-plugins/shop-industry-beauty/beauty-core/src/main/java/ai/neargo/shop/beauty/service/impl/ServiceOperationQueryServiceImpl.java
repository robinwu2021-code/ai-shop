package ai.neargo.shop.beauty.service.impl;

import ai.neargo.shop.beauty.dto.OperationVO;
import ai.neargo.shop.beauty.policy.AppointmentAccess;
import ai.neargo.shop.beauty.service.ServiceOperationQueryService;

/** Pure original-number progress read. GET never creates or executes a new command. */
public final class ServiceOperationQueryServiceImpl implements ServiceOperationQueryService {
    private final AppointmentPersistence db;
    private final AppointmentAccess access;
    public ServiceOperationQueryServiceImpl(AppointmentPersistence db, AppointmentAccess access) {
        this.db = db; this.access = access;
    }
    @Override
    public OperationVO getOperation(String entity, String store, String actor, String number) {
        access.merchant(entity, store, actor, false);
        var op = db.operation(number);
        AppointmentPersistence.require(entity.equals(op.getEntityNo()) && store.equals(op.getStoreNo()));
        return new OperationVO(number, op.getAppointmentNo(), op.getState(), op.getAction(),
                "PENDING".equals(op.getState()) ? "AUTHENTICATED_SAME_KEY_REPLAY"
                        : "RELEASE_REQUIRED".equals(op.getState()) ? "AUTHENTICATED_CANCEL" : null);
    }
}

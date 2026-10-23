package ai.neargo.shop.beauty.service.impl;

import ai.neargo.shop.beauty.dto.MyWorkOrderVO;
import ai.neargo.shop.beauty.dto.OpenWorkOrderReq;
import ai.neargo.shop.beauty.dto.ServiceSubmissionVO;
import ai.neargo.shop.beauty.dto.SubmitServicesReq;
import ai.neargo.shop.beauty.dto.WorkOrderBillVO;
import ai.neargo.shop.beauty.dto.WorkOrderDetailVO;
import ai.neargo.shop.beauty.dto.WorkOrderVO;
import ai.neargo.shop.beauty.entity.BtyWorkOrder;
import ai.neargo.shop.beauty.mapper.BeautyMappers;
import ai.neargo.shop.beauty.policy.AppointmentAccess;
import ai.neargo.shop.beauty.service.ServiceSubmissionService;
import ai.neargo.shop.beauty.service.WorkOrderBillService;
import ai.neargo.shop.beauty.service.WorkOrderService;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;

/** A local scope service keeps order preparation and authorized reads on the same domain boundary. */
public final class WorkOrderServiceImpl implements WorkOrderService {
    private final WorkOrderCoordinator coordinator;
    private final WorkOrderQuery query;
    private final AppointmentPersistence db;
    private final AppointmentAccess access;
    private final BeautyMappers.WorkOrderMapper orders;
    private final ServiceSubmissionService submissions;
    private final WorkOrderBillService bills;

    public WorkOrderServiceImpl(
            WorkOrderCoordinator coordinator,
            WorkOrderQuery query,
            AppointmentPersistence db,
            AppointmentAccess access,
            BeautyMappers.WorkOrderMapper orders,
            ServiceSubmissionService submissions,
            WorkOrderBillService bills) {
        this.coordinator = coordinator; this.query = query; this.db = db; this.access = access; this.orders = orders;
        this.submissions = submissions;
        this.bills = bills;
    }
    @Override public WorkOrderVO openWorkOrder(String entity, String store, String actor, OpenWorkOrderReq req,
            String key) { return coordinator.open(entity, store, actor, req, key); }
    @Override public WorkOrderDetailVO getWorkOrder(String entity, String store, String actor, String number) {
        access.merchant(entity, store, actor, false);
        return db.read(() -> query.detail(require(orders.selectScoped(entity, store, number))));
    }
    @Override public MyWorkOrderVO getMyWorkOrder(String user, String number) {
        access.consumer(user);
        return db.read(() -> {
            var detail = query.detail(require(orders.selectOwned(user, number)));
            return new MyWorkOrderVO(detail.workOrder(), detail.originalScopeRevision(), detail.tasks(),
                    detail.availableActions());
        });
    }

    @Override
    public ServiceSubmissionVO submitServices(
            String entity,
            String store,
            String actor,
            String workOrder,
            SubmitServicesReq req,
            String key) {
        return submissions.submitForWorkOrder(entity, store, actor, workOrder, req, key);
    }

    @Override
    public WorkOrderBillVO getBill(String entity, String store, String actor, String workOrder) {
        return bills.getBill(entity, store, actor, workOrder);
    }

    private BtyWorkOrder require(BtyWorkOrder root) {
        if (root == null) { throw BizException.of(ErrorCode.NOT_FOUND); }
        return root;
    }
}

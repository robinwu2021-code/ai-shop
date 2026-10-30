package ai.neargo.shop.beauty.client;

import ai.neargo.shop.beauty.dto.MyWorkOrderVO;
import ai.neargo.shop.beauty.dto.OpenWorkOrderReq;
import ai.neargo.shop.beauty.dto.ServiceSubmissionVO;
import ai.neargo.shop.beauty.dto.SubmitServicesReq;
import ai.neargo.shop.beauty.dto.WorkOrderBillVO;
import ai.neargo.shop.beauty.dto.WorkOrderDetailVO;
import ai.neargo.shop.beauty.dto.WorkOrderVO;
import ai.neargo.shop.beauty.service.WorkOrderService;
import ai.neargo.shop.beauty.spi.BeautyReceptionInternalApi;
import ai.neargo.shop.svc.InternalBindings;

import java.util.Objects;

/** Delegates to the same private owner without retrying uncertain business writes. */
public final class RemoteWorkOrderService implements WorkOrderService {
    private final BeautyReceptionInternalApi api;
    private final InternalBindings bindings;

    /** Both typed transport and configured binding secret are mandatory in production. */
    public RemoteWorkOrderService(BeautyReceptionInternalApi api, InternalBindings bindings) {
        this.api = Objects.requireNonNull(api);
        this.bindings = Objects.requireNonNull(bindings);
    }

    @Override
    public WorkOrderVO openWorkOrder(
            String entityNo,
            String currentStoreNo,
            String actorNo,
            OpenWorkOrderReq req,
            String idempotencyKey) {
        return BeautyReceptionCalls.call(
                "BAPI-054",
                "OPEN_WORK_ORDER",
                entityNo,
                currentStoreNo,
                actorNo,
                req.appointmentNo(),
                new BeautyReceptionInternalApi.OpenWorkOrder(req, idempotencyKey),
                bindings,
                api::openWorkOrder);
    }

    @Override
    public WorkOrderDetailVO getWorkOrder(
            String entityNo, String currentStoreNo, String actorNo, String workOrderNo) {
        return BeautyReceptionCalls.call(
                "BAPI-055",
                "GET_WORK_ORDER",
                entityNo,
                currentStoreNo,
                actorNo,
                workOrderNo,
                new BeautyReceptionInternalApi.WorkOrderRef(workOrderNo),
                bindings,
                api::getWorkOrder);
    }

    @Override
    public MyWorkOrderVO getMyWorkOrder(String userNo, String workOrderNo) {
        return BeautyReceptionCalls.call(
                "BAPI-155",
                "GET_MY_WORK_ORDER",
                null,
                null,
                userNo,
                workOrderNo,
                new BeautyReceptionInternalApi.WorkOrderRef(workOrderNo),
                bindings,
                api::getMyWorkOrder);
    }

    @Override
    public ServiceSubmissionVO submitServices(
            String entityNo,
            String currentStoreNo,
            String actorNo,
            String workOrderNo,
            SubmitServicesReq req,
            String idempotencyKey) {
        return BeautyReceptionCalls.call(
                "BAPI-056",
                "SUBMIT_SERVICES",
                entityNo,
                currentStoreNo,
                actorNo,
                workOrderNo,
                new BeautyReceptionInternalApi.SubmitServices(workOrderNo, req, idempotencyKey),
                bindings,
                api::submitServices);
    }

    @Override
    public WorkOrderBillVO getBill(
            String entityNo, String currentStoreNo, String actorNo, String workOrderNo) {
        return BeautyReceptionCalls.call(
                "BAPI-071",
                "GET_BILL",
                entityNo,
                currentStoreNo,
                actorNo,
                workOrderNo,
                new BeautyReceptionInternalApi.WorkOrderRef(workOrderNo),
                bindings,
                api::getBill);
    }
}

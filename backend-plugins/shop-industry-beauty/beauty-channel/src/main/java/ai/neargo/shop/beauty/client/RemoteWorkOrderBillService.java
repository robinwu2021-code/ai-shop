package ai.neargo.shop.beauty.client;

import ai.neargo.shop.beauty.dto.BusinessTraceVO;
import ai.neargo.shop.beauty.dto.WorkOrderBillVO;
import ai.neargo.shop.beauty.service.WorkOrderBillService;
import ai.neargo.shop.beauty.spi.BeautyReceptionInternalApi;
import ai.neargo.shop.svc.InternalBindings;

import java.util.Objects;

/** Delegates to the same private owner without retrying uncertain business writes. */
public final class RemoteWorkOrderBillService implements WorkOrderBillService {
    private final BeautyReceptionInternalApi api;
    private final InternalBindings bindings;

    /** Both typed transport and configured binding secret are mandatory in production. */
    public RemoteWorkOrderBillService(BeautyReceptionInternalApi api, InternalBindings bindings) {
        this.api = Objects.requireNonNull(api);
        this.bindings = Objects.requireNonNull(bindings);
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

    @Override
    public BusinessTraceVO getTrace(
            String entityNo, String currentStoreNo, String actorNo, String businessNo) {
        return BeautyReceptionCalls.call(
                "BAPI-137",
                "GET_TRACE",
                entityNo,
                currentStoreNo,
                actorNo,
                businessNo,
                new BeautyReceptionInternalApi.BusinessRef(businessNo),
                bindings,
                api::getTrace);
    }
}

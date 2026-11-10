package ai.neargo.shop.beauty.client;

import ai.neargo.shop.beauty.dto.WorkOrderQueryReq;
import ai.neargo.shop.beauty.dto.WorkOrderSummaryVO;
import ai.neargo.shop.beauty.service.WorkOrderWorkbenchService;
import ai.neargo.shop.beauty.spi.BeautyWorkOrderWorkbenchInternalApi;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.svc.InternalBindings;

/**
 * Typed bound transport retains the original actor, current store and query; never invents an empty
 * queue.
 */
public final class RemoteWorkOrderWorkbenchService implements WorkOrderWorkbenchService {
    private final BeautyWorkOrderWorkbenchInternalApi api;
    private final InternalBindings bindings;

    public RemoteWorkOrderWorkbenchService(
            BeautyWorkOrderWorkbenchInternalApi api, InternalBindings bindings) {
        this.api = api;
        this.bindings = bindings;
    }

    @Override
    public PageData<WorkOrderSummaryVO> list(
            String entity, String store, String actor, WorkOrderQueryReq query) {
        return BeautyReceptionCalls.call(
                BeautyWorkOrderWorkbenchInternalApi.ENDPOINT,
                BeautyWorkOrderWorkbenchInternalApi.ACTION,
                entity,
                store,
                actor,
                store,
                query,
                bindings,
                api::list);
    }
}

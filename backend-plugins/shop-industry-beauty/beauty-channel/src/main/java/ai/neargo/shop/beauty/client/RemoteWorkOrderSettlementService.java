package ai.neargo.shop.beauty.client;

import ai.neargo.shop.beauty.dto.PreviewWorkOrderSettlementReq;
import ai.neargo.shop.beauty.dto.WorkOrderSettlementPreviewVO;
import ai.neargo.shop.beauty.dto.WorkOrderSettlementVO;
import ai.neargo.shop.beauty.service.WorkOrderSettlementService;
import ai.neargo.shop.beauty.spi.BeautySettlementInternalApi;
import ai.neargo.shop.svc.InternalBindings;

public final class RemoteWorkOrderSettlementService implements WorkOrderSettlementService {
    private final BeautySettlementInternalApi api;
    private final InternalBindings bindings;
    public RemoteWorkOrderSettlementService(BeautySettlementInternalApi api, InternalBindings bindings) {
        this.api = api;
        this.bindings = bindings;
    }
    @Override
    public WorkOrderSettlementVO get(String entity, String store, String actor, String no) {
        return BeautyReceptionCalls.call(BeautySettlementInternalApi.ENDPOINT, BeautySettlementInternalApi.READ,
                entity, store, actor, no, new BeautySettlementInternalApi.ReadReq(no), bindings, api::read);
    }
    @Override
    public WorkOrderSettlementPreviewVO preview(String entity, String store, String actor, String no,
                                               PreviewWorkOrderSettlementReq request) {
        return BeautyReceptionCalls.call(BeautySettlementInternalApi.ENDPOINT, BeautySettlementInternalApi.PREVIEW,
                entity, store, actor, no, new BeautySettlementInternalApi.PreviewReq(no, request), bindings, api::preview);
    }
}

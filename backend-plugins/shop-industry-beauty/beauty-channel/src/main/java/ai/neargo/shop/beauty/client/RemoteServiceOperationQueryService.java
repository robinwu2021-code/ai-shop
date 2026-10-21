package ai.neargo.shop.beauty.client;

import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.beauty.dto.OperationVO;
import ai.neargo.shop.beauty.service.ServiceOperationQueryService;
import ai.neargo.shop.beauty.spi.BeautyBookingInternalApi;
import ai.neargo.shop.common.PageData;

/** Same authenticated service contract across a process boundary, without retrying uncertain writes. */
public final class RemoteServiceOperationQueryService implements ServiceOperationQueryService {
    private final BeautyBookingInternalApi api;
    public RemoteServiceOperationQueryService(BeautyBookingInternalApi api) { this.api = api; }
    @Override
    public OperationVO getOperation(String entityNo, String currentStoreNo, String actorNo, String operationNo) {
        DelegatedIdentityContext.outside();
        return BeautyBookingResponses.identity(BeautyServiceCalls.call(() ->
                BeautyBookingResponses.require(api.getOperation(new
                BeautyBookingInternalApi.GetOperation(entityNo, currentStoreNo, actorNo, operationNo,
                DelegatedIdentityContext.current(actorNo))))), operationNo);
    }
}

package ai.neargo.shop.beauty.client;

import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.beauty.dto.OperationVO;
import ai.neargo.shop.beauty.service.ServiceOperationRecoveryService;
import ai.neargo.shop.beauty.spi.BeautyBookingInternalApi;
import ai.neargo.shop.common.PageData;

/** Same authenticated service contract across a process boundary, without retrying uncertain writes. */
public final class RemoteServiceOperationRecoveryService implements ServiceOperationRecoveryService {
    private final BeautyBookingInternalApi api;
    public RemoteServiceOperationRecoveryService(BeautyBookingInternalApi api) { this.api = api; }
    @Override
    public OperationVO recoverOperation(String entityNo, String currentStoreNo, String actorNo, String operationNo) {
        DelegatedIdentityContext.outside();
        return BeautyBookingResponses.identity(BeautyServiceCalls.call(() ->
                BeautyBookingResponses.require(api.recoverOperation(new
                BeautyBookingInternalApi.RecoverOperation(entityNo, currentStoreNo, actorNo, operationNo,
                DelegatedIdentityContext.current(actorNo))))), operationNo);
    }
}

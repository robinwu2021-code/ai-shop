package ai.neargo.shop.food.foodclient;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.CreatePolicyReq;
import ai.neargo.shop.food.dto.GetPolicyQuery;
import ai.neargo.shop.food.dto.OperationVO;
import ai.neargo.shop.food.dto.StoreOperationPolicyResultVO;
import ai.neargo.shop.food.service.StoreOperationService;
import ai.neargo.shop.food.spi.StoreOperationInternalApi;
import ai.neargo.shop.food.spi.StorePolicyPublicationInternalApi;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;
import java.util.function.Supplier;

/** Uses the same Service contract as the local implementation; no store or policy truth is copied here. */
public class RemoteStoreOperationService implements StoreOperationService {
    private final StoreOperationInternalApi api;
    private final StorePolicyPublicationInternalApi writes;

    /** Accepts the platform HTTP client, which owns discovery, credentials and transport timeouts. */
    public RemoteStoreOperationService(StoreOperationInternalApi api, StorePolicyPublicationInternalApi writes) {
        this.api = api;
        this.writes = writes;
    }

    @Override
    public StoreOperationPolicyResultVO getPolicy(String entityNo, String storeNo, GetPolicyQuery query) {
        return invoke(() -> api.getPolicy(
                new StoreOperationInternalApi.Lookup(entityNo, storeNo, query == null ? null : query.at())));
    }

    @Override
    public OperationVO createPolicy(String entityNo, String storeNo, String actorNo,
            CreatePolicyReq req, String key) {
        return invoke(() -> writes.createPolicy(
                new StorePolicyPublicationInternalApi.Command(entityNo, storeNo, actorNo, key, req)));
    }

    private <T> T invoke(Supplier<T> call) {
        try {
            return ServiceCalls.call(ServiceName.FOOD, call);
        } catch (ServiceCallException failure) {
            ErrorCode business = switch (failure.statusCode()) {
                case 400 -> ErrorCode.BAD_REQUEST;
                case 403 -> ErrorCode.FORBIDDEN;
                case 404 -> ErrorCode.NOT_FOUND;
                case 409 -> ErrorCode.CONFLICT;
                default -> null;
            };
            if (business != null) throw BizException.of(business);
            throw failure;
        }
    }
}

package ai.neargo.shop.beauty.client;

import ai.neargo.shop.beauty.dto.OperationVO;
import ai.neargo.shop.beauty.service.ServiceOperationQueryService;
import ai.neargo.shop.beauty.spi.BeautyReceptionInternalApi;
import ai.neargo.shop.svc.InternalBindings;

import java.util.Objects;

/**
 * Original operation numbers share the strict wire across appointment, reception and source
 * families.
 */
public final class RemoteServiceOperationQueryService implements ServiceOperationQueryService {
    private final BeautyReceptionInternalApi api;
    private final InternalBindings bindings;

    /** Bound transport replaces the legacy bare-VO path for this facade. */
    public RemoteServiceOperationQueryService(
            BeautyReceptionInternalApi api, InternalBindings bindings) {
        this.api = Objects.requireNonNull(api);
        this.bindings = Objects.requireNonNull(bindings);
    }

    @Override
    public OperationVO getOperation(String entityNo, String currentStoreNo, String actorNo, String operationNo) {
        return BeautyReceptionCalls.call(
                "BAPI-160",
                "GET_OPERATION",
                entityNo,
                currentStoreNo,
                actorNo,
                operationNo,
                new BeautyReceptionInternalApi.OperationRef(operationNo),
                bindings,
                api::getOperation);
    }
}

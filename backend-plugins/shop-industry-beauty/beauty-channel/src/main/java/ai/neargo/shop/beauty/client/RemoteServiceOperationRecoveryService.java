package ai.neargo.shop.beauty.client;

import ai.neargo.shop.beauty.dto.OperationVO;
import ai.neargo.shop.beauty.service.ServiceOperationRecoveryService;
import ai.neargo.shop.beauty.spi.BeautyReceptionInternalApi;
import ai.neargo.shop.svc.InternalBindings;

import java.util.Objects;

/**
 * Original operation numbers share the strict wire across appointment, reception and source
 * families.
 */
public final class RemoteServiceOperationRecoveryService
        implements ServiceOperationRecoveryService {
    private final BeautyReceptionInternalApi api;
    private final InternalBindings bindings;

    /** Bound transport replaces the legacy bare-VO path for this facade. */
    public RemoteServiceOperationRecoveryService(
            BeautyReceptionInternalApi api, InternalBindings bindings) {
        this.api = Objects.requireNonNull(api);
        this.bindings = Objects.requireNonNull(bindings);
    }

    @Override
    public OperationVO recoverOperation(String entityNo, String currentStoreNo, String actorNo, String operationNo) {
        return BeautyReceptionCalls.call(
                "BM03-OPERATION-RECOVERY",
                "RECOVER_OPERATION",
                entityNo,
                currentStoreNo,
                actorNo,
                operationNo,
                new BeautyReceptionInternalApi.OperationRef(operationNo),
                bindings,
                api::recoverOperation);
    }
}

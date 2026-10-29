package ai.neargo.shop.beauty.port;

import ai.neargo.shop.spi.product.ServiceCatalogFactsInternalApi;
import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.spi.product.ServiceCatalogFactsPort;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;
import java.util.function.Supplier;

/** Fetches product authority before the beauty transaction, without importing product entities or Mappers. */
public class RemoteServiceCatalogFactsPort implements ServiceCatalogFactsPort {
    private final ServiceCatalogFactsInternalApi api;
    public RemoteServiceCatalogFactsPort(ServiceCatalogFactsInternalApi api) { this.api = api; }
    @Override
    public Store store(String storeNo) {
        var result = query(() -> api.store(new ServiceCatalogFactsInternalApi.StoreLookup(storeNo)));
        requireStore(result, storeNo);
        return result;
    }
    @Override
    public Service service(String storeNo, String skuNo) {
        var result = query(() -> api.service(new ServiceCatalogFactsInternalApi.ServiceLookup(storeNo, skuNo)));
        if (result == null || !skuNo.equals(result.skuNo()) || result.goodsNo() == null || result.goodsNo().isBlank()
                || result.goodsRevision() == null || result.skuRevision() == null
                || result.goodsRevision() < 0 || result.skuRevision() < 0) {
            throw new IllegalStateException("incomplete product master evidence");
        }
        requireStore(result.store(), storeNo);
        return result;
    }
    private void requireStore(Store store, String storeNo) {
        if (store == null || !storeNo.equals(store.storeNo()) || store.entityNo() == null
                || store.entityNo().isBlank() || store.status() == null) {
            throw new IllegalStateException("different product store identity");
        }
    }
    private <T> T query(Supplier<T> operation) {
        DelegatedIdentityContext.outside();
        try {
            return ServiceCalls.call(ServiceName.PLATFORM,
                    operation);
        } catch (ServiceCallException failure) {
            ErrorCode code = switch (failure.statusCode()) {
                case 400 -> ErrorCode.BAD_REQUEST;
                case 403 -> ErrorCode.FORBIDDEN;
                case 404 -> ErrorCode.NOT_FOUND;
                case 409 -> ErrorCode.CONFLICT;
                default -> null;
            };
            if (code != null) { throw BizException.of(code); }
            throw failure;
        }
    }
}

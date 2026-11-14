package ai.neargo.shop.beauty.port;

import ai.neargo.shop.spi.product.ServiceProductFactsInternalApi;
import ai.neargo.shop.spi.product.ServiceProductFactsPort;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;

/** Fetches product authority before the beauty transaction, without importing product entities or Mappers. */
public class RemoteServiceProductFactsPort implements ServiceProductFactsPort {
    private final ServiceProductFactsInternalApi api;
    public RemoteServiceProductFactsPort(ServiceProductFactsInternalApi api) { this.api = api; }
    @Override
    public Fact get(String entityNo, String storeNo, String skuNo) {
        try {
            return ServiceCalls.call(ServiceName.PLATFORM,
                    () -> api.get(new ServiceProductFactsInternalApi.Lookup(entityNo, storeNo, skuNo)));
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

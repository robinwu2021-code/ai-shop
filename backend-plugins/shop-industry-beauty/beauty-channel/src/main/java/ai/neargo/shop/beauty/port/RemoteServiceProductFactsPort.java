package ai.neargo.shop.beauty.port;

import ai.neargo.shop.spi.product.ServiceProductFactsInternalApi;
import ai.neargo.shop.spi.product.ServiceProductFactsPort;

/** Fetches product authority before the beauty transaction, without importing product entities or Mappers. */
public class RemoteServiceProductFactsPort implements ServiceProductFactsPort {
    private final ServiceProductFactsInternalApi api;
    public RemoteServiceProductFactsPort(ServiceProductFactsInternalApi api) { this.api = api; }
    @Override
    public Fact get(String entityNo, String storeNo, String skuNo) {
        return PlatformCalls.call(() -> api.get(new ServiceProductFactsInternalApi.Lookup(entityNo, storeNo, skuNo)));
    }
}

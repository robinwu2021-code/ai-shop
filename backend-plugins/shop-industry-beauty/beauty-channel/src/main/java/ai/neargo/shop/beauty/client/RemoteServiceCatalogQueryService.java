package ai.neargo.shop.beauty.client;

import ai.neargo.shop.beauty.dto.PublicServiceVO;
import ai.neargo.shop.beauty.dto.PublicStoreVO;
import ai.neargo.shop.beauty.dto.ServiceDetailQuery;
import ai.neargo.shop.beauty.dto.ServiceDetailVO;
import ai.neargo.shop.beauty.service.ServiceCatalogQueryService;
import ai.neargo.shop.beauty.spi.BeautyCatalogInternalApi;
import ai.neargo.shop.spi.product.ServiceCatalogFactsInternalApi;

/** Public reads never invent a merchant actor; business reads retain the authenticated delegation. */
public class RemoteServiceCatalogQueryService implements ServiceCatalogQueryService {
    private final BeautyCatalogInternalApi api;
    public RemoteServiceCatalogQueryService(BeautyCatalogInternalApi api) { this.api = api; }
    @Override
    public PublicStoreVO getPublicStore(String storeNo) {
        return BeautyServiceCalls.call(() ->
                api.getPublicStore(new ServiceCatalogFactsInternalApi.StoreLookup(storeNo)));
    }
    @Override
    public PublicServiceVO getPublicService(String skuNo, ServiceDetailQuery query) {
        return BeautyServiceCalls.call(() ->
                api.getPublicService(new BeautyCatalogInternalApi.PublicLookup(skuNo, query)));
    }
    @Override
    public ServiceDetailVO getService(String entityNo, String storeNo, String actorNo,
            String skuNo, ServiceDetailQuery query) {
        return BeautyServiceCalls.call(() -> api.getService(new BeautyCatalogInternalApi.MerchantLookup(
                entityNo, storeNo, actorNo, skuNo, query)));
    }
}

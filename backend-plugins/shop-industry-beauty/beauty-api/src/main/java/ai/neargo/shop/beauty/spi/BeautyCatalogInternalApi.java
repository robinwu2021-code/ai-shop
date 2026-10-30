package ai.neargo.shop.beauty.spi;

import ai.neargo.shop.beauty.dto.PublicServiceVO;
import ai.neargo.shop.beauty.dto.PublicStoreVO;
import ai.neargo.shop.beauty.dto.ServiceDetailQuery;
import ai.neargo.shop.beauty.dto.ServiceDetailVO;
import ai.neargo.shop.spi.product.ServiceCatalogFactsInternalApi;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/** Typed catalog transport distinguishes public lookups from authenticated merchant delegation. */
public interface BeautyCatalogInternalApi {
    String STORE_PATH = "/internal/beauty/catalog/store";
    String PUBLIC_SERVICE_PATH = "/internal/beauty/catalog/public-service";
    String SERVICE_PATH = "/internal/beauty/catalog/service";
    @PostExchange(STORE_PATH)
    PublicStoreVO getPublicStore(@RequestBody ServiceCatalogFactsInternalApi.StoreLookup request);
    @PostExchange(PUBLIC_SERVICE_PATH)
    PublicServiceVO getPublicService(@RequestBody PublicLookup request);
    @PostExchange(SERVICE_PATH)
    ServiceDetailVO getService(@RequestBody MerchantLookup request);

    record PublicLookup(String skuNo, ServiceDetailQuery query) {
        public PublicLookup {
            new ServiceCatalogFactsInternalApi.ServiceLookup(query == null ? null : query.storeNo(), skuNo);
        }
    }
    record MerchantLookup(String entityNo, String storeNo, String actorNo, String skuNo, ServiceDetailQuery query) {
        public MerchantLookup {
            BeautyDelegatedScope.require(entityNo, storeNo, actorNo);
            new ServiceCatalogFactsInternalApi.ServiceLookup(query == null ? null : query.storeNo(), skuNo);
        }
    }
}

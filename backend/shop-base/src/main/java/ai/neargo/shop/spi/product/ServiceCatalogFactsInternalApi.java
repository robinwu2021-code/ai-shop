package ai.neargo.shop.spi.product;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/** Only a credentialed industry service may query the PLATFORM public catalog projection. */
public interface ServiceCatalogFactsInternalApi {
    String STORE_PATH = "/internal/product/service-catalog/store";
    String SERVICE_PATH = "/internal/product/service-catalog/service";
    @PostExchange(STORE_PATH)
    ServiceCatalogFactsPort.Store store(@RequestBody StoreLookup request);
    @PostExchange(SERVICE_PATH)
    ServiceCatalogFactsPort.Service service(@RequestBody ServiceLookup request);

    record StoreLookup(String storeNo) {
        public StoreLookup { require(storeNo); }
    }
    record ServiceLookup(String storeNo, String skuNo) {
        public ServiceLookup { require(storeNo); require(skuNo); }
    }
    private static void require(String value) {
        if (value == null || value.isBlank() || value.length() > 32 || !value.equals(value.strip())) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }
}

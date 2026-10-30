package ai.neargo.shop.beauty.service;

import ai.neargo.shop.beauty.dto.PublicServiceVO;
import ai.neargo.shop.beauty.dto.PublicStoreVO;
import ai.neargo.shop.beauty.dto.ServiceDetailQuery;
import ai.neargo.shop.beauty.dto.ServiceDetailVO;

/** Read-only catalog authority; all sale and store facts come from the platform owners. */
public interface ServiceCatalogQueryService {
    PublicStoreVO getPublicStore(String storeNo);
    PublicServiceVO getPublicService(String skuNo, ServiceDetailQuery query);
    ServiceDetailVO getService(String entityNo, String currentStoreNo, String actorNo,
                              String skuNo, ServiceDetailQuery query);
}

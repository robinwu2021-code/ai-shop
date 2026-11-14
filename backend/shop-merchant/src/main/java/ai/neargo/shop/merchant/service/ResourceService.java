package ai.neargo.shop.merchant.service;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.merchant.dto.ResourceQuery;
import ai.neargo.shop.merchant.dto.ResourceVO;
import ai.neargo.shop.merchant.dto.SaveResourceReq;

/** One shared resource owner serves retail, food and beauty without duplicating personnel capacity. */
public interface ResourceService {
    /** Current-store directory includes assigned STAFF and suspended records for maintenance. */
    PageData<ResourceVO> listResources(String entityNo, String currentStoreNo, String actorNo, ResourceQuery query);
    /** Physical identities remain fixed; occupied resources cannot silently invalidate existing commitments. */
    ResourceVO saveResource(String entityNo, String currentStoreNo, String actorNo, SaveResourceReq request,
            String idempotencyKey);
}

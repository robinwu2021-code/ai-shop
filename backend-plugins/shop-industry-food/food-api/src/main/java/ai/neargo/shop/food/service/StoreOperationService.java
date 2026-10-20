package ai.neargo.shop.food.service;

import ai.neargo.shop.food.dto.GetPolicyQuery;
import ai.neargo.shop.food.dto.StoreOperationPolicyResultVO;

/** Restaurant policy operations; add later write methods only with their actual accepted implementation. */
public interface StoreOperationService {
    /** Reads the latest policy effective at the requested instant within the already authorized store scope. */
    StoreOperationPolicyResultVO getPolicy(String entityNo, String storeNo, GetPolicyQuery query);
}

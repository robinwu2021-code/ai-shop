package ai.neargo.shop.food.service;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.dto.DiningBatchSummaryVO;
import ai.neargo.shop.food.dto.ListDiningBatchesQuery;

/** Bounded source listing; original order or payment state must be requested from its own authority separately. */
public interface DiningBatchQueryService {
    PageData<DiningBatchSummaryVO> list(String entityNo,String storeNo,String actorNo,ListDiningBatchesQuery query);
}

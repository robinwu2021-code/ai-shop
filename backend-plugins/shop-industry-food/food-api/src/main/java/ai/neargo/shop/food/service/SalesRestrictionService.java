package ai.neargo.shop.food.service;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.dto.SalesRestrictionReq;
import ai.neargo.shop.food.dto.SalesRestrictionQuery;
import ai.neargo.shop.food.dto.SalesRestrictionVO;
import ai.neargo.shop.food.dto.SalesRestrictionEvaluationVO;

/** Owns only restaurant restrictions, keeping original inventory authority separate. */
public interface SalesRestrictionService {
    SalesRestrictionVO manage(String entity, String store, String actor, SalesRestrictionReq req, String key);
    PageData<SalesRestrictionVO> list(String entity, String store, SalesRestrictionQuery query);
    SalesRestrictionEvaluationVO evaluate(String entity, String store, String sku, String source, String fulfillment);
}

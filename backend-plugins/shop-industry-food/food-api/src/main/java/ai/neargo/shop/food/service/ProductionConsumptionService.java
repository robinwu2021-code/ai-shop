package ai.neargo.shop.food.service;

import ai.neargo.shop.food.dto.ProductionContracts.ConsumptionVO;
import ai.neargo.shop.food.dto.ProductionContracts.ListQuery;
import ai.neargo.shop.common.PageData;

/** Commands accept an original START event identity only; material quantities cannot be supplied by a caller. */
public interface ProductionConsumptionService {
    /** Persist original theory; current finished-SKU plans remain BLOCKED until a genuine exclusive raw-leaf plan exists. */
    ConsumptionVO consume(String entity,String store,String actor,String sourceEventNo);
    /** SQL-paged original projections only; never queries or posts inventory. */
    PageData<ConsumptionVO> list(String entity,String store,String actor,ListQuery query);
    /** Read the durable local evidence without making an inventory request. */
    ConsumptionVO get(String entity,String store,String actor,String sourceEventNo);
}

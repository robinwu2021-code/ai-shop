package ai.neargo.shop.food.service;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.dto.ProductionContracts;
import ai.neargo.shop.food.dto.ProductionContracts.ConsumptionVO;
import ai.neargo.shop.food.dto.ProductionContracts.ListQuery;

/**
 * Commands accept an original START event identity only; material quantities cannot be supplied by
 * a caller.
 */
public interface ProductionConsumptionService {
    /** Execute an original event through its frozen exclusive inventory plan. */
    ConsumptionVO consume(String entity, String store, String actor, String sourceEventNo);

    default ConsumptionVO adjust(
            String entity,
            String store,
            String actor,
            String taskNo,
            ProductionContracts.AdjustmentReq req,
            String key) {
        throw new UnsupportedOperationException();
    }

    default ConsumptionVO reverse(
            String entity,
            String store,
            String actor,
            String eventNo,
            ProductionContracts.ReverseReq req,
            String key) {
        throw new UnsupportedOperationException();
    }

    default int recover(int limit) {
        throw new UnsupportedOperationException();
    }

    /** SQL-paged original projections only; never queries or posts inventory. */
    PageData<ConsumptionVO> list(String entity, String store, String actor, ListQuery query);

    /** Read the durable local evidence without making an inventory request. */
    ConsumptionVO get(String entity, String store, String actor, String sourceEventNo);
}

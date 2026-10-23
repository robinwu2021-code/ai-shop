package ai.neargo.shop.food.service;

import ai.neargo.shop.food.dto.CartAccess;
import ai.neargo.shop.food.dto.CreateDiningOrderReq;
import ai.neargo.shop.food.dto.DiningOrderBatchVO;

/** Original-key restaurant placement and source binding; core remains the order and payment owner. */
public interface DiningOrderService {
    DiningOrderBatchVO customerPlace(String store, CartAccess access, CreateDiningOrderReq request, String key);
    DiningOrderBatchVO customerGet(CartAccess access, String batchNo);
    DiningOrderBatchVO staffGet(String entity, String store, String actor, String batchNo);
    /** Backend replay of this persisted source and original consent; no current login or fresh financial command. */
    DiningOrderBatchVO recoverOriginal(String entity, String store, String number);
}

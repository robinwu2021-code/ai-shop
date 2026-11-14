package ai.neargo.shop.food.service;

import ai.neargo.shop.food.dto.KitchenReturnContracts.HandleReq;
import ai.neargo.shop.food.dto.KitchenReturnContracts.HandledVO;
import ai.neargo.shop.food.dto.KitchenReturnContracts.ReturnKitchenVO;

/** Only local service disposition is handled here; no permission to approve a financial after-sale is implied. */
public interface KitchenReturnService {
    ReturnKitchenVO get(String entity,String store,String returnNo);
    HandledVO handle(String entity,String store,String actor,String returnNo,HandleReq req,String key);
}

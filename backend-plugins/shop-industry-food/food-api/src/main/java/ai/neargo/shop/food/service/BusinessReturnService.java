package ai.neargo.shop.food.service;

import ai.neargo.shop.food.dto.BusinessReturnContracts.CancelReq;
import ai.neargo.shop.food.dto.BusinessReturnContracts.CreateReq;
import ai.neargo.shop.food.dto.BusinessReturnContracts.ReturnVO;

/** Employee business return intentions reserve only local original quantities, never money or inventory. */
public interface BusinessReturnService {
    ReturnVO create(String entity,String store,String actor,String batch,CreateReq request,String key);
    ReturnVO get(String entity,String store,String number);
    ReturnVO cancel(String entity,String store,String actor,String number,CancelReq request,String key);
}

package ai.neargo.shop.food.service;

import ai.neargo.shop.food.dto.BusinessShareContracts.CancelReq;
import ai.neargo.shop.food.dto.BusinessShareContracts.ConfirmReq;
import ai.neargo.shop.food.dto.BusinessShareContracts.CreateReq;
import ai.neargo.shop.food.dto.BusinessShareContracts.PlanVO;

/** Authenticated staff responsibility plans do not reserve core receivables or authorize customer settlement. */
public interface BusinessShareService {
    PlanVO create(String entity,String store,String actor,String check,CreateReq request,String key);
    PlanVO get(String entity,String store,String check,String plan);
    PlanVO confirm(String entity,String store,String actor,String check,String plan,ConfirmReq request,String key);
    PlanVO cancel(String entity,String store,String actor,String check,String plan,CancelReq request,String key);
}

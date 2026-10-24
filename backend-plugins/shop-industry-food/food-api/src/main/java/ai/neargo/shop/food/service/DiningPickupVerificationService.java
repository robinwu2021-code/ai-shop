package ai.neargo.shop.food.service;

import ai.neargo.shop.food.dto.DiningPickupContracts.PickupVerificationVO;
import ai.neargo.shop.food.dto.DiningPickupContracts.VerifyPickupReq;

/** Authorized FOOD batch resolves the sole original source/root/suborder reference for trade verification. */
public interface DiningPickupVerificationService {
    PickupVerificationVO verify(String entity,String store,String actor,String batch,VerifyPickupReq req,String key);
    PickupVerificationVO get(String entity,String store,String actor,String batch,String key);
}

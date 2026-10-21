package ai.neargo.shop.food.service;

import ai.neargo.shop.food.dto.DishResultVO;
import ai.neargo.shop.food.dto.SaveDishReq;

/** Owns restaurant attributes only and delegates product ownership to the product authority. */
public interface DishService {
    /** Resolves authoritative ownership, then atomically writes extension, audit, outbox and replay receipt. */
    DishResultVO saveDish(String entityNo, String storeNo, String goodsNo, String actorNo,
            SaveDishReq req, String idempotencyKey);
}

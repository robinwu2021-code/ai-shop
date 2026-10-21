package ai.neargo.shop.food.service;

import ai.neargo.shop.food.dto.DishResultVO;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.dto.DishCatalogItemVO;
import ai.neargo.shop.food.dto.DishDetailResultVO;
import ai.neargo.shop.food.dto.ListDishesQuery;
import ai.neargo.shop.food.dto.SaveDishReq;

/** Owns restaurant attributes only and delegates product ownership to the product authority. */
public interface DishService {
    /** Returns a stable page and accurate count after authoritative product visibility and title matching. */
    PageData<DishCatalogItemVO> listDishes(String entityNo, String storeNo, ListDishesQuery query);

    /** Reads the current option-group definition; no fake empty groups or sale authorization. */
    DishDetailResultVO getDish(String entityNo, String storeNo, String goodsNo);

    /** Resolves authoritative ownership, then atomically writes extension, audit, outbox and replay receipt. */
    DishResultVO saveDish(String entityNo, String storeNo, String goodsNo, String actorNo,
            SaveDishReq req, String idempotencyKey);
}

package ai.neargo.shop.food.lifecycle;

import ai.neargo.shop.food.dto.CartVO;
import ai.neargo.shop.food.dto.DiningPriceFacts;

/** Source-owner local snapshot extension; no external endpoint accepts an asserted fee source. */
public interface FoodFeeSourceLifecycle {
    DiningPriceFacts attach(String entity, String store, CartVO cart, DiningPriceFacts facts);
}

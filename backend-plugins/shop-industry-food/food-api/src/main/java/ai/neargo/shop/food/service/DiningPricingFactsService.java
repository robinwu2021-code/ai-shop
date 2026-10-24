package ai.neargo.shop.food.service;

import ai.neargo.shop.food.dto.CartAccess;
import ai.neargo.shop.food.dto.DiningPriceFacts;

/** Authenticates original carts through CheckCartService; callers supply no prices or tax rates. */
public interface DiningPricingFactsService {
    DiningPriceFacts staff(String entity, String store, String cart, long expectedVersion);
    DiningPriceFacts customer(String cart, CartAccess access, long expectedVersion);
}

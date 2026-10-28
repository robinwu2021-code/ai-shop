package ai.neargo.shop.food.commerce.service;

import ai.neargo.shop.food.commerce.dto.FoodCatalogPricePolicyContracts.Facts;
import ai.neargo.shop.food.commerce.dto.FoodCatalogPricePolicyContracts.Query;

/** A policy key alone never authorizes external menu prices or grants order/payment qualification. */
public interface FoodCatalogPricePolicyService {
    Facts capture(Query query);
}

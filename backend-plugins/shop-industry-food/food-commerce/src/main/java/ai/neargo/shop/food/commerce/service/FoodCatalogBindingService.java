package ai.neargo.shop.food.commerce.service;

import ai.neargo.shop.food.commerce.dto.FoodCatalogBindingContracts.Facts;
import ai.neargo.shop.food.commerce.dto.FoodCatalogBindingContracts.Query;

/** A separate composition service keeps FOOD dependencies out of the generic commerce kernel. */
public interface FoodCatalogBindingService {
    /** Capture current identity evidence; this does not authorize publishing a complete catalog. */
    Facts capture(Query query);
}

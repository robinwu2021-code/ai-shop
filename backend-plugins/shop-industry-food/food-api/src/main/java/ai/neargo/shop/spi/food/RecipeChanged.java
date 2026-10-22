package ai.neargo.shop.spi.food;

import ai.neargo.shop.event.DomainEvent;
import ai.neargo.shop.food.dto.RecipeVO;

/** Atomic draft invalidation; this event is never a stock consumption command. */
public record RecipeChanged(String entityNo, String storeNo, String recipeNo, String action,
        long version, String operationNo, RecipeVO after) implements DomainEvent {
    @Override public String aggregateType() { return "FOOD_RECIPE"; }
    @Override public String aggregateId() { return recipeNo; }
    @Override public String eventType() { return "FOOD_RECIPE_CHANGED"; }
}

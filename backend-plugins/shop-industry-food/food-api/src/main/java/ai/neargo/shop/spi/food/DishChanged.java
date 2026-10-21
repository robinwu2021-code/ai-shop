package ai.neargo.shop.spi.food;

import ai.neargo.shop.event.DomainEvent;
import ai.neargo.shop.food.dto.DishVO;

/** Configuration facts are separate from menu publication and never signal inventory consumption. */
public record DishChanged(String entityNo, String storeNo, String goodsNo, String action, long version,
        String operationNo, DishVO before, DishVO after) implements DomainEvent {
    @Override public String aggregateType() { return "FOOD_DISH"; }
    @Override public String aggregateId() { return goodsNo; }
    @Override public String eventType() { return "FOOD_DISH_CHANGED"; }
}

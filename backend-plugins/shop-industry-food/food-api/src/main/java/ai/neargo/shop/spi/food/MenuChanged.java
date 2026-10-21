package ai.neargo.shop.spi.food;

import ai.neargo.shop.event.DomainEvent;
import ai.neargo.shop.food.dto.MenuVO;

/** Atomic draft invalidation; never a publication or channel synchronization acknowledgment. */
public record MenuChanged(String entityNo, String storeNo, String menuNo, String action,
        long version, String operationNo, MenuVO after) implements DomainEvent {
    @Override public String aggregateType() { return "FOOD_MENU"; }
    @Override public String aggregateId() { return menuNo; }
    @Override public String eventType() { return "FOOD_MENU_CHANGED"; }
}

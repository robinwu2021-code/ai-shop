package ai.neargo.shop.spi.food;

import ai.neargo.shop.event.DomainEvent;
import ai.neargo.shop.food.dto.ModifierConfigVO;

/** Configuration invalidation fact, never a sale or inventory instruction. */
public record ModifierChanged(String entityNo, String storeNo, String modifierNo, String action,
        long version, String operationNo, ModifierConfigVO after) implements DomainEvent {
    @Override public String aggregateType() { return "FOOD_MODIFIER"; }
    @Override public String aggregateId() { return modifierNo; }
    @Override public String eventType() { return "FOOD_MODIFIER_CHANGED"; }
}

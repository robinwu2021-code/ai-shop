package ai.neargo.shop.spi.food;

import ai.neargo.shop.event.DomainEvent;
import ai.neargo.shop.food.dto.MenuChannelBindingVO;

/** Binding invalidation records configuration only, preserving external order history. */
public record MenuChannelBindingChanged(String entityNo,String storeNo,String bindingNo,String action,
        long version,String operationNo,MenuChannelBindingVO after) implements DomainEvent {
    @Override public String aggregateType() { return "FOOD_MENU_CHANNEL_BINDING"; }
    @Override public String aggregateId() { return bindingNo; }
    @Override public String eventType() { return "FOOD_MENU_CHANNEL_BINDING_CHANGED"; }
}

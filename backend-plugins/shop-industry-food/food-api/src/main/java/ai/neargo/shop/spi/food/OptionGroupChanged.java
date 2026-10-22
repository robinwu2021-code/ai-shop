package ai.neargo.shop.spi.food;

import ai.neargo.shop.event.DomainEvent;
import ai.neargo.shop.food.dto.OptionGroupConfigVO;

/** A draft configuration fact never triggers stock movements or pretends a group was published. */
public record OptionGroupChanged(String entityNo, String storeNo, String groupNo, String action,
        long version, String operationNo, OptionGroupConfigVO after) implements DomainEvent {
    @Override public String aggregateType() { return "FOOD_OPTION_GROUP"; }
    @Override public String aggregateId() { return groupNo; }
    @Override public String eventType() { return "FOOD_OPTION_GROUP_CHANGED"; }
}

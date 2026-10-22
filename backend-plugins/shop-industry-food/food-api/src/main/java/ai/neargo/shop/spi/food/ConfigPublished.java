package ai.neargo.shop.spi.food;

import ai.neargo.shop.event.DomainEvent;
import ai.neargo.shop.food.dto.ConfigRevisionVO;

/** Publication intent is persisted atomically; downstream consumers still resolve by scope and effective time. */
public record ConfigPublished(String entityNo, String storeNo, String operationNo,
        ConfigRevisionVO publication) implements DomainEvent {
    @Override public String aggregateType() { return "FOOD_CONFIG"; }
    @Override public String aggregateId() { return publication.configKind() + ":" + publication.configNo(); }
    @Override public String eventType() { return "FOOD_CONFIG_PUBLISHED"; }
}

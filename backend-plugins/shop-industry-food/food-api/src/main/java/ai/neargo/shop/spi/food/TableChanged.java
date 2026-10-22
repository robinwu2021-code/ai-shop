package ai.neargo.shop.spi.food;

import ai.neargo.shop.event.DomainEvent;
import ai.neargo.shop.food.dto.TableVO;

/** Actual configuration and archive facts preserve audit contents without creating another history table. */
public record TableChanged(String entityNo, String storeNo, String tableNo, String action, long version,
        String operationNo, TableVO before, TableVO after, String reason) implements DomainEvent {
    @Override public String aggregateType() { return "FOOD_TABLE"; }
    @Override public String aggregateId() { return tableNo; }
    @Override public String eventType() { return "FOOD_TABLE_CHANGED"; }
}

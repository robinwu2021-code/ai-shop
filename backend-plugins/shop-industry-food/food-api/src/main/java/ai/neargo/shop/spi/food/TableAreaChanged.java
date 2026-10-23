package ai.neargo.shop.spi.food;

import ai.neargo.shop.event.DomainEvent;
import ai.neargo.shop.food.dto.TableAreaVO;

/** A real configuration change, distinct from no-op acceptance and downstream delivery. */
public record TableAreaChanged(String entityNo, String storeNo, String areaNo, String action,
        long version, String operationNo, TableAreaVO before, TableAreaVO after, String reason) implements DomainEvent {
    @Override public String aggregateType() { return "FOOD_TABLE_AREA"; }
    @Override public String aggregateId() { return areaNo; }
    @Override public String eventType() { return "FOOD_TABLE_AREA_CHANGED"; }
}

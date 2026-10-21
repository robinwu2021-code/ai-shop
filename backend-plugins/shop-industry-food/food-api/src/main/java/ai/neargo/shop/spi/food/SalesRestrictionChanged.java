package ai.neargo.shop.spi.food;

import ai.neargo.shop.event.DomainEvent;
import ai.neargo.shop.food.dto.SalesRestrictionVO;

/** Restriction invalidation is committed with its audit and never acknowledges inventory changes. */
public record SalesRestrictionChanged(String entityNo, String storeNo, String restrictionNo, String action,
        String operationNo, SalesRestrictionVO after) implements DomainEvent {
    @Override public String aggregateType() { return "FOOD_SALES_RESTRICTION"; }
    @Override public String aggregateId() { return restrictionNo; }
    @Override public String eventType() { return "FOOD_SALES_RESTRICTION_CHANGED"; }
}

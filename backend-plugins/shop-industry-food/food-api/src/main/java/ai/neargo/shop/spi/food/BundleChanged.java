package ai.neargo.shop.spi.food;

import ai.neargo.shop.event.DomainEvent;
import ai.neargo.shop.food.dto.BundleConfigVO;

/** Draft invalidation is an atomic configuration fact, not a financial or inventory instruction. */
public record BundleChanged(String entityNo, String storeNo, String skuNo, String action,
        long version, String operationNo, BundleConfigVO after) implements DomainEvent {
    @Override public String aggregateType() { return "FOOD_BUNDLE"; }
    @Override public String aggregateId() { return skuNo; }
    @Override public String eventType() { return "FOOD_BUNDLE_CHANGED"; }
}

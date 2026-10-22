package ai.neargo.shop.spi.food;

import ai.neargo.shop.event.DomainEvent;

/** Reference-only draft invalidation; no credential, identity or pricing snapshot is exposed. */
public record CartChanged(String entityNo, String storeNo, String cartNo, String action,
        long version, String operationNo) implements DomainEvent {
    @Override public String aggregateType() { return "FOOD_CART"; }
    @Override public String aggregateId() { return cartNo; }
    @Override public String eventType() { return "FOOD_CART_CHANGED"; }
}

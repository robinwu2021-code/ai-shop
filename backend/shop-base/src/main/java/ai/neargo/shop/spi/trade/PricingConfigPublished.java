package ai.neargo.shop.spi.trade;

import ai.neargo.shop.event.DomainEvent;

/** Immutable revision identity invalidates pointers without asking a consumer to reconstruct a mutable draft. */
public record PricingConfigPublished(String tenantNo, String resourceNo, String revisionNo, String effectiveHash,
        String entityNo, String storeNo, String actorNo, boolean template) implements DomainEvent {
    @Override public String aggregateType() { return "PRICING_CONFIG"; }
    @Override public String aggregateId() { return resourceNo; }
    @Override public String eventType() { return "PRICING_CONFIG_PUBLISHED"; }
}

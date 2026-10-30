package ai.neargo.shop.spi.resource;

import ai.neargo.shop.event.DomainEvent;

/** Immutable maintenance evidence, without credentials, free text or a promise of availability. */
public record ResourceChanged(String aggregateId, String entityNo, String storeNo, String action,
        long revision, String requestRef) implements DomainEvent {
    @Override
    public String aggregateType() { return "MERCHANT_RESOURCE"; }
    @Override
    public String eventType() { return "MERCHANT_RESOURCE_CHANGED"; }
}

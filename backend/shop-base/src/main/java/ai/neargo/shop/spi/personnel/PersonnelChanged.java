package ai.neargo.shop.spi.personnel;

import ai.neargo.shop.event.DomainEvent;

/** Immutable maintenance evidence only; no customer data or session token is included. */
public record PersonnelChanged(String aggregateId, String entityNo, String storeNo, String action,
        long revision, String requestRef) implements DomainEvent {
    @Override
    public String aggregateType() { return "MERCHANT_PERSONNEL"; }
    @Override
    public String eventType() { return "MERCHANT_PERSONNEL_CHANGED"; }
}

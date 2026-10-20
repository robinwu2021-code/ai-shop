package ai.neargo.shop.spi.entitlement;

import ai.neargo.shop.event.DomainEvent;

/** Versioned, typed pay-to-core event contract. Implementations intentionally expose only approved business fields. */
public sealed interface PayEntitlementEvent extends DomainEvent
        permits EntitlementEvents.LegacyCouponIssueCompleted, EntitlementEvents.EntitlementGranted, EntitlementEvents.EntitlementUsed,
                EntitlementEvents.StoredValueCredited, EntitlementEvents.EntitlementUseReversed,
                EntitlementEvents.EntitlementBatchPrepared, EntitlementEvents.StoredValueRechargeReceived,
                EntitlementEvents.StoredValueRechargeCancelled,EntitlementEvents.ExceptionOpened,EntitlementEvents.LegacyCouponUsageChanged {
    String eventNo();
    String entityNo();
    int schemaVersion();
    long occurredAt();
}

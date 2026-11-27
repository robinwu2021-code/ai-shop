package ai.neargo.shop.spi.entitlement;

/** Return only after the original eventNo and immutable facts are durably accepted (broker confirm or core outbox row). */
public interface PayEntitlementEventBridgePort {
    void publish(String eventNo, PayEntitlementEvent event);
}

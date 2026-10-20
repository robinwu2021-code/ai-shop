package ai.neargo.shop.spi.entitlement;

/** Return only after core has durably accepted the original eventNo and immutable facts. */
public interface PayEntitlementEventBridgePort {
    void publish(String eventNo, PayEntitlementEvent event);
}

package ai.neargo.shop.spi.entitlement;

import ai.neargo.shop.event.DomainEvent;

/** Safe source references only; recovery re-reads authoritative core/pay facts. */
public final class LegacyCouponCheckoutEvents {
    private LegacyCouponCheckoutEvents() { }
    public record Decided(String tenantNo,String entityNo,String checkoutNo,String operationNo) implements DomainEvent {
        @Override public String aggregateType() {return "LEGACY_COUPON_CHECKOUT";}
        @Override public String aggregateId() {return checkoutNo;}
        @Override public String eventType() {return "LEGACY_COUPON_CHECKOUT_DECIDED";}
    }
}

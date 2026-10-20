package ai.neargo.shop.spi.entitlement;
import ai.neargo.shop.event.DomainEvent;
public final class LegacyCouponHistoryEvents {
    private LegacyCouponHistoryEvents() { }
    public record ReturnDecided(String tenantNo,String entityNo,String returnNo) implements DomainEvent {
        @Override public String aggregateType() {return "LEGACY_COUPON_HISTORY_RETURN";}
        @Override public String aggregateId() {return returnNo;}
        @Override public String eventType() {return "LEGACY_COUPON_HISTORY_RETURN_DECIDED";}
    }
}

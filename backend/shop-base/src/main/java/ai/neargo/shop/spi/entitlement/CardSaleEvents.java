package ai.neargo.shop.spi.entitlement;
import ai.neargo.shop.event.DomainEvent;
/** Durable order cancellation signal; pay still verifies the original source decision before releasing. */
public final class CardSaleEvents {
    private CardSaleEvents() { }
    public record Cancelled(String orderNo) implements DomainEvent {
        public Cancelled { ContractChecks.id(orderNo); }
        @Override public String aggregateType() { return "ORDER"; }
        @Override public String aggregateId() { return orderNo; }
        @Override public String eventType() { return "CARD_SALE_CANCELLED"; }
    }
}

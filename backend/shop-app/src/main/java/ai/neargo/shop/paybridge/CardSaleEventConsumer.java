package ai.neargo.shop.paybridge;

import ai.neargo.shop.event.OutboxConsumer;
import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.trade.OrderEvents;
import ai.neargo.shop.spi.entitlement.CardSaleEvents;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Acknowledge paid delivery only after every frozen right is issued; failures use existing outbox retries. */
@Component
public class CardSaleEventConsumer implements OutboxConsumer {
    @Override public String handlerId() { return "card-sale-recovery"; }
    // Original owner/action ledgers provide replay safety across PAY calls and independent core writes.
    @Override public boolean externalWorkflow() { return true; }

    private final CardSaleCoordinator coordinator;
    private final ObjectMapper json;
    public CardSaleEventConsumer(CardSaleCoordinator coordinator,ObjectMapper json) {
        this.coordinator=coordinator; this.json=json;
    }
    @Override public boolean supports(String type) { return "ORDER_PAID".equals(type) || "CARD_SALE_CANCELLED".equals(type); }
    @Override @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public void consume(SysOutbox event) {
        if(!supports(event.getEventType())) throw new IllegalArgumentException("Unsupported sale event");
        boolean cancelled="CARD_SALE_CANCELLED".equals(event.getEventType());
        String no=cancelled ? json.readValue(event.getPayload(),CardSaleEvents.Cancelled.class).orderNo()
                : json.readValue(event.getPayload(),OrderEvents.OrderPaid.class).orderNo();
        if(!no.equals(event.getAggregateId())) throw new IllegalArgumentException("Order identity mismatch");
        var ctx=new EntitlementContext("MAIN","OUTBOX",no,"0".repeat(64));
        var progress=coordinator.recover(ctx,no);
        if((cancelled ? progress.released() : progress.issued())!=progress.total()) throw new IllegalStateException("Original sale issuance is pending");
    }
}

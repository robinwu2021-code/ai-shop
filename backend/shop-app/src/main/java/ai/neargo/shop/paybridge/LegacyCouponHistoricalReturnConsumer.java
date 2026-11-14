package ai.neargo.shop.paybridge;
import ai.neargo.shop.event.OutboxConsumer;
import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.spi.entitlement.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.ObjectMapper;
/** Durable core decision drives pay restoration and a separate core acknowledgement. */
@Component
public class LegacyCouponHistoricalReturnConsumer implements OutboxConsumer {
    @Override public String handlerId() { return "legacy-coupon-historical-return"; }
    // Original owner/action ledgers provide replay safety across PAY calls and independent core writes.
    @Override public boolean externalWorkflow() { return true; }

    private final LegacyCouponHistoricalReturnCoordinator coordinator;
    private final ObjectMapper json;
    public LegacyCouponHistoricalReturnConsumer(LegacyCouponHistoricalReturnCoordinator coordinator,ObjectMapper json) {
        this.coordinator=coordinator;this.json=json;
    }
    public boolean supports(String type) {return "LEGACY_COUPON_HISTORY_RETURN_DECIDED".equals(type);}
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public void consume(SysOutbox event) {
        var fact=json.readValue(event.getPayload(),LegacyCouponHistoryEvents.ReturnDecided.class);
        coordinator.complete(new EntitlementContext(fact.tenantNo(),"HISTORY_RETURN_OUTBOX",fact.returnNo(),"0".repeat(64)),fact.entityNo(),fact.returnNo());
    }
}

package ai.neargo.shop.paybridge;

import ai.neargo.shop.event.OutboxConsumer;
import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.trade.service.LegacyCouponOrderCreationCoordinator;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.LegacyCouponCheckoutEvents;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Core decision and this outbox entry commit together. Retry never creates a replacement financial operation. */
@Component
public class LegacyCouponCheckoutDecisionConsumer implements OutboxConsumer {
    private final LegacyCouponOrderCreationCoordinator coordinator;
    private final ObjectMapper json;
    public LegacyCouponCheckoutDecisionConsumer(LegacyCouponOrderCreationCoordinator coordinator,ObjectMapper json) {
        this.coordinator=coordinator;this.json=json;
    }
    @Override public boolean supports(String type) {return "LEGACY_COUPON_CHECKOUT_DECIDED".equals(type);}
    @Override @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public void consume(SysOutbox event) {
        var fact=json.readValue(event.getPayload(),LegacyCouponCheckoutEvents.Decided.class);
        var ctx=new EntitlementContext(fact.tenantNo(),"OUTBOX",fact.checkoutNo(),"0".repeat(64));
        coordinator.recover(ctx,fact.checkoutNo());
    }
}

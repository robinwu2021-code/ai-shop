package ai.neargo.shop.paybridge;

import ai.neargo.shop.event.OutboxConsumer;
import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.trade.OrderEvents.AfterSaleRefunded;
import ai.neargo.shop.trade.service.LegacyCouponCheckoutJournal;
import ai.neargo.shop.trade.service.LegacyCouponHistoricalReturnService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Read a fresh core snapshot after refund commit, so concurrent sibling refunds cannot leave the coupon stranded. */
@Component
public class LegacyCouponRefundCompletedConsumer implements OutboxConsumer {
    private final LegacyCouponCheckoutJournal journal;
    private final ObjectMapper json;
    @Autowired private LegacyCouponHistoricalReturnService historical;
    public LegacyCouponRefundCompletedConsumer(LegacyCouponCheckoutJournal journal,ObjectMapper json) {this.journal=journal;this.json=json;}
    @Override public boolean supports(String type) {return "AFTER_SALE_REFUNDED".equals(type);}
    @Override @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public void consume(SysOutbox event) {
        var fact=json.readValue(event.getPayload(),AfterSaleRefunded.class);
        // Legacy trade events are MAIN-only and do not carry a tenant; source SQL still binds MAIN explicitly.
        var ctx=new EntitlementContext("MAIN","REFUND_OUTBOX",fact.afterSaleNo(),"0".repeat(64));
        journal.returnRefundedOrder(ctx,fact.afterSaleNo());
        if(historical!=null) historical.refunded(ctx,fact.afterSaleNo());
    }
}
